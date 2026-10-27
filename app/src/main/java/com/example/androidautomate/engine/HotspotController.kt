package com.example.androidautomate.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.example.androidautomate.data.RoutineRepository
import com.example.androidautomate.model.HotspotMethod
import com.example.androidautomate.service.AutomationAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.resume

object HotspotController {

    private const val WIFI_AP_STATE_DISABLED = 11
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * Checks if Wi-Fi Hotspot (SoftAP) is currently active using WifiManager AP state
     * or dedicated SoftAP network interfaces (`ap0`, `softap0`, `swlan0`).
     */
    fun isHotspotCurrentlyEnabled(context: Context): Boolean? {
        // 1. Check WifiManager.getWifiApState via reflection
        runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val getStateMethod = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            getStateMethod.isAccessible = true
            val state = getStateMethod.invoke(wifiManager) as? Int
            if (state != null) {
                if (state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING) return true
                if (state == WIFI_AP_STATE_DISABLED) return false
            }
        }

        // 2. Check dedicated SoftAP network interfaces (excluding wlan1 which is used by dual-band Wi-Fi STA)
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val apInterfaceActive = interfaces.any { iface ->
                val name = iface.name.lowercase()
                val isDedicatedAp = name.startsWith("ap") ||
                    name.startsWith("softap") ||
                    name.startsWith("swlan")
                isDedicatedAp && iface.isUp && iface.inetAddresses.hasMoreElements()
            }
            if (apInterfaceActive) return true
        }

        return null
    }

    /**
     * Main entry point to toggle Mobile Hotspot ON or OFF.
     * Returns Pair(success, humanReadableMessage).
     */
    suspend fun setHotspotEnabled(
        context: Context,
        enable: Boolean
    ): Pair<Boolean, String> {
        val repo = RoutineRepository.getInstance(context)
        val method = repo.hotspotMethod.value
        val customTileLabel = repo.customHotspotTileLabel.value

        return when (method) {
            HotspotMethod.ROOT_SHELL -> {
                toggleViaRootShell(enable)
            }
            HotspotMethod.ACCESSIBILITY_SETTINGS_SCREEN -> {
                toggleViaAccessibility(
                    context = context,
                    enable = enable,
                    customTileLabel = customTileLabel,
                    preferSettingsScreen = true
                )
            }
            HotspotMethod.ACCESSIBILITY_QUICK_TILE -> {
                toggleViaAccessibility(
                    context = context,
                    enable = enable,
                    customTileLabel = customTileLabel,
                    preferSettingsScreen = false
                )
            }
            HotspotMethod.AUTO -> {
                // 1. Try silent system API reflection first (works on some custom ROMs / older Android)
                val silentSuccess = trySilentSystemTetheringReflection(context, enable)
                if (silentSuccess) {
                    return true to "Toggled Hotspot ${if (enable) "ON" else "OFF"} silently via System Tethering API."
                }
                // 2. Use Accessibility Service Quick Settings / Tethering Settings auto-toggle
                toggleViaAccessibility(
                    context = context,
                    enable = enable,
                    customTileLabel = customTileLabel,
                    preferSettingsScreen = false
                )
            }
        }
    }

    private suspend fun toggleViaAccessibility(
        context: Context,
        enable: Boolean,
        customTileLabel: String,
        preferSettingsScreen: Boolean
    ): Pair<Boolean, String> {
        val a11yService = AutomationAccessibilityService.ensureServiceBound(context)
        if (a11yService == null) {
            val isEnabledInSettings = AutomationAccessibilityService.isServiceEnabled(context)
            return if (isEnabledInSettings) {
                false to "Accessibility Service is enabled in Settings but not yet bound by Android. Toggle it off and back on in Accessibility Settings."
            } else {
                false to "Accessibility Service is not enabled. Tap 'Enable' on the Home screen so AutoPulse can toggle the Hotspot tile."
            }
        }

        val result = withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                if (preferSettingsScreen) {
                    a11yService.performSettingsScreenHotspotToggle(enable) { ok, msg ->
                        if (cont.isActive) cont.resume(ok to msg)
                    }
                } else {
                    a11yService.performQuickSettingsHotspotToggle(
                        targetEnabled = enable,
                        customTileLabel = customTileLabel,
                        useSettingsScreenFallback = true
                    ) { ok, msg ->
                        if (cont.isActive) cont.resume(ok to msg)
                    }
                }
            }
        }

        return result ?: (false to "Timed out waiting for Quick Settings Hotspot toggle.")
    }

    private fun trySilentSystemTetheringReflection(context: Context, enable: Boolean): Boolean {
        return runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (!enable) {
                val stopMethod = cm.javaClass.getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
                stopMethod.isAccessible = true
                stopMethod.invoke(cm, 0) // TETHERING_WIFI = 0
                val afterState = isHotspotCurrentlyEnabled(context)
                return afterState == false
            }
            false
        }.getOrDefault(false)
    }

    private suspend fun toggleViaRootShell(enable: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = if (enable) {
                "cmd connectivity start-tethering wifi || cmd wifi start-softap"
            } else {
                "cmd connectivity stop-tethering wifi || cmd wifi stop-softap"
            }
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                true to "Hotspot turned ${if (enable) "ON" else "OFF"} via Root shell (`$cmd`)."
            } else {
                false to "Root command exited with code $exitCode. Switch Hotspot Method to 'Smart Auto' if your phone is not rooted."
            }
        }.getOrElse { e ->
            false to "Root shell not available (${e.localizedMessage ?: "No su binary"}). Use 'Smart Auto (Accessibility)' mode on unrooted phones."
        }
    }
}
