package com.citrusd365.autopulse.data

import android.content.Context
import com.citrusd365.autopulse.model.ActionType
import com.citrusd365.autopulse.model.ExecutionLogEntry
import com.citrusd365.autopulse.model.HotspotMethod
import com.citrusd365.autopulse.model.LogSeverity
import com.citrusd365.autopulse.model.Routine
import com.citrusd365.autopulse.model.RoutineAction
import com.citrusd365.autopulse.model.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoutineRepository private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private val _logs = MutableStateFlow<List<ExecutionLogEntry>>(emptyList())
    val logs: StateFlow<List<ExecutionLogEntry>> = _logs.asStateFlow()

    private val _engineEnabled = MutableStateFlow(true)
    val engineEnabled: StateFlow<Boolean> = _engineEnabled.asStateFlow()

    private val _hotspotMethod = MutableStateFlow(HotspotMethod.AUTO)
    val hotspotMethod: StateFlow<HotspotMethod> = _hotspotMethod.asStateFlow()

    private val _customHotspotTileLabel = MutableStateFlow("Hotspot")
    val customHotspotTileLabel: StateFlow<String> = _customHotspotTileLabel.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        _engineEnabled.value = prefs.getBoolean(KEY_ENGINE_ENABLED, true)
        val methodStr = prefs.getString(KEY_HOTSPOT_METHOD, HotspotMethod.AUTO.name) ?: HotspotMethod.AUTO.name
        _hotspotMethod.value = runCatching { HotspotMethod.valueOf(methodStr) }.getOrDefault(HotspotMethod.AUTO)
        _customHotspotTileLabel.value = prefs.getString(KEY_CUSTOM_TILE_LABEL, "Hotspot") ?: "Hotspot"

        val routinesRaw = prefs.getString(KEY_ROUTINES, null)
        if (routinesRaw == null) {
            val defaultRoutines = createDefaultRoutines()
            _routines.value = defaultRoutines
            saveRoutinesInternal(defaultRoutines)
        } else {
            _routines.value = runCatching {
                json.decodeFromString<List<Routine>>(routinesRaw)
            }.getOrElse { createDefaultRoutines() }
        }

        val logsRaw = prefs.getString(KEY_LOGS, null)
        if (logsRaw != null) {
            _logs.value = runCatching {
                json.decodeFromString<List<ExecutionLogEntry>>(logsRaw)
            }.getOrDefault(emptyList())
        }
    }

    private fun createDefaultRoutines(): List<Routine> {
        return listOf(
            Routine(
                id = "default-car-hotspot",
                name = "Car Bluetooth → Mobile Hotspot",
                description = "Turns Wi-Fi Hotspot ON when connected to your car's Bluetooth, and turns it OFF when disconnected.",
                enabled = true,
                triggerType = TriggerType.BLUETOOTH_DEVICE,
                bluetoothTargetName = "My Car",
                bluetoothTargetAddress = "",
                disconnectDelaySeconds = 10,
                onEnterActions = listOf(
                    RoutineAction(
                        type = ActionType.SET_HOTSPOT,
                        boolValue = true
                    ),
                    RoutineAction(
                        type = ActionType.SET_MEDIA_VOLUME,
                        intValue = 90
                    )
                ),
                onExitActions = listOf(
                    RoutineAction(
                        type = ActionType.SET_HOTSPOT,
                        boolValue = false
                    )
                )
            )
        )
    }

    fun setEngineEnabled(enabled: Boolean) {
        _engineEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENGINE_ENABLED, enabled).apply()
    }

    fun setHotspotMethod(method: HotspotMethod) {
        _hotspotMethod.value = method
        prefs.edit().putString(KEY_HOTSPOT_METHOD, method.name).apply()
    }

    fun setCustomHotspotTileLabel(label: String) {
        val cleaned = label.trim().ifBlank { "Hotspot" }
        _customHotspotTileLabel.value = cleaned
        prefs.edit().putString(KEY_CUSTOM_TILE_LABEL, cleaned).apply()
    }

    fun upsertRoutine(routine: Routine) {
        val current = _routines.value.toMutableList()
        val index = current.indexOfFirst { it.id == routine.id }
        if (index >= 0) {
            current[index] = routine
        } else {
            current.add(0, routine)
        }
        _routines.value = current
        saveRoutinesInternal(current)
    }

    fun deleteRoutine(routineId: String) {
        val updated = _routines.value.filterNot { it.id == routineId }
        _routines.value = updated
        saveRoutinesInternal(updated)
    }

    fun toggleRoutineEnabled(routineId: String, enabled: Boolean) {
        val updated = _routines.value.map {
            if (it.id == routineId) {
                it.copy(
                    enabled = enabled,
                    isCurrentlyActive = if (!enabled) false else it.isCurrentlyActive
                )
            } else {
                it
            }
        }
        _routines.value = updated
        saveRoutinesInternal(updated)
    }

    fun markRoutineActiveState(routineId: String, isActive: Boolean, triggeredAtMs: Long? = null) {
        val updated = _routines.value.map {
            if (it.id == routineId) {
                it.copy(
                    isCurrentlyActive = isActive,
                    lastTriggeredEpochMs = triggeredAtMs ?: it.lastTriggeredEpochMs
                )
            } else {
                it
            }
        }
        _routines.value = updated
        saveRoutinesInternal(updated)
    }

    fun appendLog(
        routineName: String,
        title: String,
        details: String,
        severity: LogSeverity = LogSeverity.INFO
    ) {
        val entry = ExecutionLogEntry(
            routineName = routineName,
            title = title,
            details = details,
            severity = severity
        )
        val updated = (listOf(entry) + _logs.value).take(MAX_LOG_ENTRIES)
        _logs.value = updated
        runCatching {
            prefs.edit().putString(KEY_LOGS, json.encodeToString(updated)).apply()
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        prefs.edit().remove(KEY_LOGS).apply()
    }

    private fun saveRoutinesInternal(list: List<Routine>) {
        runCatching {
            prefs.edit().putString(KEY_ROUTINES, json.encodeToString(list)).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "autopulse_routines_prefs"
        private const val KEY_ROUTINES = "routines_json"
        private const val KEY_LOGS = "logs_json"
        private const val KEY_ENGINE_ENABLED = "engine_enabled"
        private const val KEY_HOTSPOT_METHOD = "hotspot_method"
        private const val KEY_CUSTOM_TILE_LABEL = "custom_tile_label"
        private const val MAX_LOG_ENTRIES = 120

        @Volatile
        private var INSTANCE: RoutineRepository? = null

        fun getInstance(context: Context): RoutineRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RoutineRepository(context).also { INSTANCE = it }
            }
        }
    }
}
