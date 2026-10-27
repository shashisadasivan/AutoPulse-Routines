package com.example.androidautomate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.androidautomate.MainActivity
import com.example.androidautomate.R
import com.example.androidautomate.data.RoutineRepository
import com.example.androidautomate.engine.BluetoothHelper
import com.example.androidautomate.engine.RoutineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiverRegistered = false

    private val dynamicEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = extractBluetoothDevice(intent) ?: return
                    RoutineEngine.onBluetoothDeviceConnected(context, device)
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = extractBluetoothDevice(intent) ?: return
                    RoutineEngine.onBluetoothDeviceDisconnected(context, device)
                }

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                    val device = extractBluetoothDevice(intent) ?: return
                    if (state == BluetoothProfile.STATE_CONNECTED && prevState != BluetoothProfile.STATE_CONNECTED) {
                        RoutineEngine.onBluetoothDeviceConnected(context, device)
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED && prevState == BluetoothProfile.STATE_CONNECTED) {
                        RoutineEngine.onBluetoothDeviceDisconnected(context, device)
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    RoutineEngine.onChargingStateChanged(context, isCharging = true)
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    RoutineEngine.onChargingStateChanged(context, isCharging = false)
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) {
                        val pct = (level * 100) / scale
                        RoutineEngine.onBatteryLevelChanged(context, pct)
                    }
                }

                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    @Suppress("DEPRECATION")
                    val connected = netInfo?.isConnected == true
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = runCatching {
                        wifiManager?.connectionInfo?.ssid?.trim('"').orEmpty()
                    }.getOrDefault("")
                    RoutineEngine.onWifiStateChanged(context, connected, ssid)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundSafely("Monitoring Bluetooth & system events")
        registerDynamicReceivers()

        val repo = RoutineRepository.getInstance(applicationContext)
        serviceScope.launch {
            repo.routines.collectLatest { routines ->
                val activeCount = routines.count { it.enabled && it.isCurrentlyActive }
                val enabledCount = routines.count { it.enabled }
                val statusText = when {
                    activeCount > 0 -> {
                        val names = routines.filter { it.enabled && it.isCurrentlyActive }.joinToString { it.name }
                        "Active now: $names"
                    }
                    enabledCount > 0 -> "Watching $enabledCount enabled routine(s)"
                    else -> "No routines currently enabled"
                }
                updateNotification(statusText)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        AutomationAccessibilityService.tryAutoEnableIfPermitted(applicationContext)
        startForegroundSafely("Watching Bluetooth & device triggers")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AutomationAccessibilityService.tryAutoEnableIfPermitted(applicationContext)
        if (RoutineRepository.getInstance(applicationContext).engineEnabled.value) {
            startOrUpdate(applicationContext)
        }
    }

    override fun onDestroy() {
        isRunning = false
        if (receiverRegistered) {
            runCatching { unregisterReceiver(dynamicEventReceiver) }
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerDynamicReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            dynamicEventReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun startForegroundSafely(contentText: String) {
        createNotificationChannel()
        val notification = buildNotification(contentText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (BluetoothHelper.hasBluetoothPermission(this)) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Fallback to specialUse if connectedDevice FGS type is rejected on strict OEM builds
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun updateNotification(contentText: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(contentText))
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_autopulse)
            .setContentTitle("AutoPulse Routines")
            .setContentText(contentText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoPulse Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth & Routine event detection active in the background."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val CHANNEL_ID = "autopulse_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.example.androidautomate.STOP_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startOrUpdate(context: Context) {
            val repo = RoutineRepository.getInstance(context)
            if (!repo.engineEnabled.value) return
            val intent = Intent(context, AutomationForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                runCatching { context.stopService(Intent(context, AutomationForegroundService::class.java)) }
            }
        }
    }
}
