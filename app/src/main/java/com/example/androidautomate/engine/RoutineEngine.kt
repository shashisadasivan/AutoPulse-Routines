package com.example.androidautomate.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.androidautomate.R
import com.example.androidautomate.data.RoutineRepository
import com.example.androidautomate.model.ActionType
import com.example.androidautomate.model.LogSeverity
import com.example.androidautomate.model.RingerModeOption
import com.example.androidautomate.model.Routine
import com.example.androidautomate.model.RoutineAction
import com.example.androidautomate.model.TriggerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object RoutineEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pendingDisconnectJobs = ConcurrentHashMap<String, Job>()
    private var keepAwakeWakeLock: PowerManager.WakeLock? = null

    fun onBluetoothDeviceConnected(context: Context, device: BluetoothDevice) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val deviceName = BluetoothHelper.getDeviceNameSafely(context, device)
        val deviceAddress = runCatching { device.address }.getOrNull().orEmpty()

        val matchingRoutines = repo.routines.value.filter { routine ->
            routine.enabled &&
                routine.triggerType == TriggerType.BLUETOOTH_DEVICE &&
                BluetoothHelper.matchesRoutineTarget(
                    context = context,
                    device = device,
                    targetAddress = routine.bluetoothTargetAddress,
                    targetName = routine.bluetoothTargetName
                )
        }

        for (routine in matchingRoutines) {
            // Cancel any pending disconnect countdown if the car reconnected within the grace period
            val cancelledPendingExit = pendingDisconnectJobs.remove(routine.id)?.let { job ->
                job.cancel()
                true
            } ?: false

            if (cancelledPendingExit && routine.isCurrentlyActive) {
                repo.appendLog(
                    routineName = routine.name,
                    title = "Reconnected Within Grace Period",
                    details = "$deviceName ($deviceAddress) reconnected before disconnect timer expired. Keeping routine active.",
                    severity = LogSeverity.INFO
                )
                continue
            }

            scope.launch {
                activateRoutine(
                    context = context,
                    routine = routine,
                    reason = "Bluetooth connected: $deviceName ($deviceAddress)"
                )
            }
        }
    }

    fun onBluetoothDeviceDisconnected(context: Context, device: BluetoothDevice) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val deviceName = BluetoothHelper.getDeviceNameSafely(context, device)
        val deviceAddress = runCatching { device.address }.getOrNull().orEmpty()

        val matchingRoutines = repo.routines.value.filter { routine ->
            routine.enabled &&
                routine.triggerType == TriggerType.BLUETOOTH_DEVICE &&
                BluetoothHelper.matchesRoutineTarget(
                    context = context,
                    device = device,
                    targetAddress = routine.bluetoothTargetAddress,
                    targetName = routine.bluetoothTargetName
                )
        }

        for (routine in matchingRoutines) {
            pendingDisconnectJobs.remove(routine.id)?.cancel()

            val delaySec = routine.disconnectDelaySeconds.coerceIn(0, 300)
            if (delaySec > 0) {
                repo.appendLog(
                    routineName = routine.name,
                    title = "Bluetooth Disconnected (${delaySec}s grace period)",
                    details = "$deviceName disconnected. Waiting ${delaySec}s before running Exit actions in case of brief reconnect.",
                    severity = LogSeverity.INFO
                )
            }

            val job = scope.launch {
                if (delaySec > 0) {
                    delay(delaySec * 1000L)
                }
                pendingDisconnectJobs.remove(routine.id)
                deactivateRoutine(
                    context = context,
                    routine = routine,
                    reason = "Bluetooth disconnected: $deviceName ($deviceAddress)"
                )
            }
            pendingDisconnectJobs[routine.id] = job
        }
    }

    fun onChargingStateChanged(context: Context, isCharging: Boolean) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val matching = repo.routines.value.filter {
            it.enabled && it.triggerType == TriggerType.CHARGING_STATE
        }

        for (routine in matching) {
            scope.launch {
                if (isCharging && !routine.isCurrentlyActive) {
                    activateRoutine(context, routine, "Power connected")
                } else if (!isCharging && routine.isCurrentlyActive) {
                    deactivateRoutine(context, routine, "Power disconnected")
                }
            }
        }
    }

    fun onWifiStateChanged(context: Context, isConnected: Boolean, ssid: String) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val matching = repo.routines.value.filter { routine ->
            if (!routine.enabled || routine.triggerType != TriggerType.WIFI_STATE) return@filter false
            val filter = routine.wifiSsidFilter.trim()
            filter.isBlank() || ssid.contains(filter, ignoreCase = true)
        }

        for (routine in matching) {
            scope.launch {
                if (isConnected && !routine.isCurrentlyActive) {
                    activateRoutine(context, routine, "Wi-Fi connected${if (ssid.isNotBlank()) " ($ssid)" else ""}")
                } else if (!isConnected && routine.isCurrentlyActive) {
                    deactivateRoutine(context, routine, "Wi-Fi disconnected")
                }
            }
        }
    }

    fun onBatteryLevelChanged(context: Context, batteryPercent: Int) {
        val repo = RoutineRepository.getInstance(context)
        if (!repo.engineEnabled.value) return

        val matching = repo.routines.value.filter {
            it.enabled && it.triggerType == TriggerType.BATTERY_LOW
        }

        for (routine in matching) {
            val isBelow = batteryPercent <= routine.batteryThresholdPercent
            scope.launch {
                if (isBelow && !routine.isCurrentlyActive) {
                    activateRoutine(context, routine, "Battery at $batteryPercent% (≤ ${routine.batteryThresholdPercent}%)")
                } else if (!isBelow && routine.isCurrentlyActive) {
                    deactivateRoutine(context, routine, "Battery recovered to $batteryPercent%")
                }
            }
        }
    }

    /**
     * Allows the user to manually test either the Enter (Connected) or Exit (Disconnected)
     * actions of any routine directly from the UI.
     */
    fun triggerManualRun(context: Context, routine: Routine, runEnterActions: Boolean) {
        scope.launch {
            if (runEnterActions) {
                activateRoutine(context, routine, "Manual test trigger (Enter / Connected actions)")
            } else {
                deactivateRoutine(context, routine, "Manual test trigger (Exit / Disconnected actions)")
            }
        }
    }

    private suspend fun activateRoutine(context: Context, routine: Routine, reason: String) {
        val repo = RoutineRepository.getInstance(context)
        repo.markRoutineActiveState(routine.id, isActive = true, triggeredAtMs = System.currentTimeMillis())
        repo.appendLog(
            routineName = routine.name,
            title = "Routine Activated",
            details = reason,
            severity = LogSeverity.INFO
        )

        for (action in routine.onEnterActions) {
            executeSingleAction(context, routine.name, action)
        }
    }

    private suspend fun deactivateRoutine(context: Context, routine: Routine, reason: String) {
        val repo = RoutineRepository.getInstance(context)
        repo.markRoutineActiveState(routine.id, isActive = false, triggeredAtMs = System.currentTimeMillis())
        repo.appendLog(
            routineName = routine.name,
            title = "Routine Ended",
            details = reason,
            severity = LogSeverity.INFO
        )

        for (action in routine.onExitActions) {
            executeSingleAction(context, routine.name, action)
        }
    }

    private suspend fun executeSingleAction(
        context: Context,
        routineName: String,
        action: RoutineAction
    ) {
        val repo = RoutineRepository.getInstance(context)
        try {
            when (action.type) {
                ActionType.SET_HOTSPOT -> {
                    val (ok, msg) = HotspotController.setHotspotEnabled(context, action.boolValue)
                    repo.appendLog(
                        routineName = routineName,
                        title = action.summary(),
                        details = msg,
                        severity = if (ok) LogSeverity.SUCCESS else LogSeverity.WARNING
                    )
                }

                ActionType.SET_WIFI -> {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val changed = runCatching { wifiManager.setWifiEnabled(action.boolValue) }.getOrDefault(false)
                    if (changed || wifiManager.isWifiEnabled == action.boolValue) {
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Wi-Fi is now ${if (action.boolValue) "ON" else "OFF"}.",
                            severity = LogSeverity.SUCCESS
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // On Android 10+, open Internet connectivity panel if direct toggle was blocked
                        val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(panelIntent) }
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Opened Android Wi-Fi quick panel (Android 10+ restricts silent Wi-Fi toggle when not triggered via Hotspot).",
                            severity = LogSeverity.INFO
                        )
                    }
                }

                ActionType.SET_MEDIA_VOLUME -> {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = ((action.intValue.coerceIn(0, 100) / 100f) * maxVol).roundToInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                    repo.appendLog(
                        routineName = routineName,
                        title = action.summary(),
                        details = "Media volume set to $targetVol / $maxVol (${action.intValue}%).",
                        severity = LogSeverity.SUCCESS
                    )
                }

                ActionType.SET_RINGER_MODE -> {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val option = RingerModeOption.entries.getOrNull(action.intValue) ?: RingerModeOption.NORMAL
                    val modeConstant = when (option) {
                        RingerModeOption.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                        RingerModeOption.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        RingerModeOption.SILENT -> AudioManager.RINGER_MODE_SILENT
                    }
                    audioManager.ringerMode = modeConstant
                    repo.appendLog(
                        routineName = routineName,
                        title = action.summary(),
                        details = "Ringer mode updated to ${option.label}.",
                        severity = LogSeverity.SUCCESS
                    )
                }

                ActionType.SET_DND -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Do Not Disturb access not granted. Grant it in the Permissions banner.",
                            severity = LogSeverity.WARNING
                        )
                    } else {
                        nm.setInterruptionFilter(
                            if (action.boolValue) {
                                NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            } else {
                                NotificationManager.INTERRUPTION_FILTER_ALL
                            }
                        )
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Do Not Disturb ${if (action.boolValue) "enabled" else "disabled"}.",
                            severity = LogSeverity.SUCCESS
                        )
                    }
                }

                ActionType.KEEP_SCREEN_AWAKE -> {
                    if (action.boolValue) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (keepAwakeWakeLock?.isHeld != true) {
                            @Suppress("DEPRECATION")
                            keepAwakeWakeLock = pm.newWakeLock(
                                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                                "AutoPulse:KeepScreenAwake"
                            ).apply {
                                acquire(4 * 60 * 60 * 1000L) // Up to 4 hours driving session
                            }
                        }
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Screen will stay awake while routine is active.",
                            severity = LogSeverity.SUCCESS
                        )
                    } else {
                        runCatching {
                            if (keepAwakeWakeLock?.isHeld == true) {
                                keepAwakeWakeLock?.release()
                            }
                        }
                        keepAwakeWakeLock = null
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Released Keep Screen Awake lock.",
                            severity = LogSeverity.SUCCESS
                        )
                    }
                }

                ActionType.LAUNCH_APP -> {
                    val pkg = action.stringValue.trim()
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Launched package $pkg.",
                            severity = LogSeverity.SUCCESS
                        )
                    } else {
                        repo.appendLog(
                            routineName = routineName,
                            title = action.summary(),
                            details = "Could not find launchable activity for package '$pkg'.",
                            severity = LogSeverity.WARNING
                        )
                    }
                }

                ActionType.SEND_NOTIFICATION -> {
                    val msg = action.stringValue.ifBlank { "$routineName triggered" }
                    postRoutineAlertNotification(context, routineName, msg)
                    repo.appendLog(
                        routineName = routineName,
                        title = action.summary(),
                        details = "Displayed notification: $msg",
                        severity = LogSeverity.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            repo.appendLog(
                routineName = routineName,
                title = "Action Failed: ${action.summary()}",
                details = e.localizedMessage ?: e.javaClass.simpleName,
                severity = LogSeverity.ERROR
            )
        }
    }

    private fun postRoutineAlertNotification(context: Context, title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "autopulse_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Routine Action Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt() + 2000, notification)
    }
}
