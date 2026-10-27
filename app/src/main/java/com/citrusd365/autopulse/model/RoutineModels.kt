package com.citrusd365.autopulse.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TriggerType(val title: String, val subtitle: String) {
    BLUETOOTH_DEVICE(
        title = "Bluetooth Device Connected",
        subtitle = "When connected to your Car or specific Bluetooth device (runs Exit actions on disconnect)"
    ),
    CHARGING_STATE(
        title = "Charging State",
        subtitle = "When phone is plugged into power or wireless charger"
    ),
    WIFI_STATE(
        title = "Wi-Fi Connection",
        subtitle = "When connected to or disconnected from Wi-Fi"
    ),
    BATTERY_LOW(
        title = "Battery Below Threshold",
        subtitle = "When battery level drops below a chosen percentage"
    ),
    TIME_WINDOW(
        title = "Time Schedule",
        subtitle = "During a specific time window of the day"
    )
}

@Serializable
enum class ChargingSourceOption(val label: String) {
    ANY("Any Charger"),
    AC("Wall / Fast Charger (AC)"),
    USB("Car / USB Port"),
    WIRELESS("Wireless Charger")
}

@Serializable
enum class RingerModeOption(val label: String) {
    NORMAL("Normal (Sound On)"),
    VIBRATE("Vibrate Only"),
    SILENT("Silent")
}

@Serializable
enum class HotspotMethod(val title: String, val description: String) {
    AUTO(
        title = "Smart Auto (Recommended)",
        description = "Checks current state first, tries direct API, then uses Accessibility Quick Settings tile or Tethering Settings."
    ),
    ACCESSIBILITY_QUICK_TILE(
        title = "Quick Settings Tile (Accessibility)",
        description = "Briefly opens Quick Settings shade, checks if Hotspot tile is On/Off, taps it if needed, and closes the shade."
    ),
    ACCESSIBILITY_SETTINGS_SCREEN(
        title = "Tethering Settings Switch (Accessibility)",
        description = "Opens the system Hotspot & Tethering screen, flips the Wi-Fi Hotspot switch, and returns."
    ),
    ROOT_SHELL(
        title = "Root / ADB Shell (su)",
        description = "Runs `cmd connectivity start-tethering wifi` silently in the background (requires Root)."
    )
}

@Serializable
enum class ActionType(val title: String, val category: String) {
    SET_HOTSPOT("Wi-Fi Hotspot (Tethering)", "Connectivity"),
    SET_WIFI("Wi-Fi", "Connectivity"),
    SET_MEDIA_VOLUME("Media Volume", "Audio & Sound"),
    SET_RINGER_MODE("Ringer Mode", "Audio & Sound"),
    SET_DND("Do Not Disturb", "Audio & Sound"),
    KEEP_SCREEN_AWAKE("Keep Screen Awake", "Display & Car"),
    LAUNCH_APP("Launch Application", "Apps"),
    SEND_NOTIFICATION("Show Status Alert", "System")
}

@Serializable
data class RoutineAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val boolValue: Boolean = true,
    val intValue: Int = 80,
    val stringValue: String = "",
    val secondaryStringValue: String = ""
) {
    fun summary(): String = when (type) {
        ActionType.SET_HOTSPOT -> if (boolValue) "Turn Mobile Hotspot ON" else "Turn Mobile Hotspot OFF"
        ActionType.SET_WIFI -> if (boolValue) "Turn Wi-Fi ON" else "Turn Wi-Fi OFF"
        ActionType.SET_MEDIA_VOLUME -> "Set Media Volume to $intValue%"
        ActionType.SET_RINGER_MODE -> {
            val mode = RingerModeOption.entries.getOrNull(intValue) ?: RingerModeOption.NORMAL
            "Set Ringer to ${mode.label}"
        }
        ActionType.SET_DND -> if (boolValue) "Enable Do Not Disturb" else "Disable Do Not Disturb"
        ActionType.KEEP_SCREEN_AWAKE -> if (boolValue) "Keep Screen Awake ON" else "Release Keep Screen Awake"
        ActionType.LAUNCH_APP -> {
            val label = secondaryStringValue.ifBlank { stringValue.ifBlank { "Selected App" } }
            "Launch $label"
        }
        ActionType.SEND_NOTIFICATION -> "Notify: \"${stringValue.ifBlank { "Routine executed" }}\""
    }
}

@Serializable
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val isCurrentlyActive: Boolean = false,
    val triggerType: TriggerType = TriggerType.BLUETOOTH_DEVICE,
    // Bluetooth trigger config
    val bluetoothTargetAddress: String = "",
    val bluetoothTargetName: String = "",
    // Disconnect grace period in seconds (e.g. 10s so engine cranking doesn't drop hotspot)
    val disconnectDelaySeconds: Int = 5,
    // Charging trigger config
    val chargingSource: ChargingSourceOption = ChargingSourceOption.ANY,
    // Wi-Fi trigger config
    val wifiSsidFilter: String = "",
    // Battery threshold config
    val batteryThresholdPercent: Int = 20,
    // Time schedule config
    val startHour: Int = 8,
    val startMinute: Int = 0,
    val endHour: Int = 18,
    val endMinute: Int = 0,
    // Actions when condition starts (e.g. Car Bluetooth Connected)
    val onEnterActions: List<RoutineAction> = emptyList(),
    // Actions when condition ends (e.g. Car Bluetooth Disconnected)
    val onExitActions: List<RoutineAction> = emptyList(),
    val lastTriggeredEpochMs: Long? = null
) {
    fun triggerSummary(): String = when (triggerType) {
        TriggerType.BLUETOOTH_DEVICE -> {
            val target = when {
                bluetoothTargetName.isNotBlank() && bluetoothTargetAddress.isNotBlank() ->
                    "$bluetoothTargetName ($bluetoothTargetAddress)"
                bluetoothTargetName.isNotBlank() -> bluetoothTargetName
                bluetoothTargetAddress.isNotBlank() -> bluetoothTargetAddress
                else -> "Any Car / Audio Bluetooth Device"
            }
            "When Bluetooth connects to $target"
        }
        TriggerType.CHARGING_STATE -> "When charging (${chargingSource.label})"
        TriggerType.WIFI_STATE -> if (wifiSsidFilter.isBlank()) {
            "When connected to any Wi-Fi network"
        } else {
            "When connected to Wi-Fi \"$wifiSsidFilter\""
        }
        TriggerType.BATTERY_LOW -> "When battery falls below $batteryThresholdPercent%"
        TriggerType.TIME_WINDOW -> String.format(
            "Between %02d:%02d and %02d:%02d",
            startHour, startMinute, endHour, endMinute
        )
    }
}

@Serializable
enum class LogSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

@Serializable
data class ExecutionLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val routineName: String,
    val title: String,
    val details: String,
    val severity: LogSeverity = LogSeverity.INFO
)

data class PairedBluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isAudioOrCarClass: Boolean,
    val isCurrentlyConnected: Boolean = false
)
