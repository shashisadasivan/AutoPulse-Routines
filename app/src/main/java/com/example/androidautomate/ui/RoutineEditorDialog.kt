package com.example.androidautomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.androidautomate.engine.BluetoothHelper
import com.example.androidautomate.model.ActionType
import com.example.androidautomate.model.ChargingSourceOption
import com.example.androidautomate.model.PairedBluetoothDeviceInfo
import com.example.androidautomate.model.RingerModeOption
import com.example.androidautomate.model.Routine
import com.example.androidautomate.model.RoutineAction
import com.example.androidautomate.model.TriggerType
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineEditorDialog(
    initialRoutine: Routine,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(initialRoutine.name) }
    var description by remember { mutableStateOf(initialRoutine.description) }
    var triggerType by remember { mutableStateOf(initialRoutine.triggerType) }
    var btName by remember { mutableStateOf(initialRoutine.bluetoothTargetName) }
    var btAddress by remember { mutableStateOf(initialRoutine.bluetoothTargetAddress) }
    var disconnectDelaySec by remember { mutableIntStateOf(initialRoutine.disconnectDelaySeconds) }
    var chargingSource by remember { mutableStateOf(initialRoutine.chargingSource) }
    var wifiSsid by remember { mutableStateOf(initialRoutine.wifiSsidFilter) }
    var batteryThreshold by remember { mutableIntStateOf(initialRoutine.batteryThresholdPercent) }
    var onEnterActions by remember { mutableStateOf(initialRoutine.onEnterActions) }
    var onExitActions by remember { mutableStateOf(initialRoutine.onExitActions) }

    var pairedDevices by remember { mutableStateOf(BluetoothHelper.getPairedDevices(context)) }
    var showAddActionForEnter by remember { mutableStateOf<Boolean?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (initialRoutine.name.isBlank()) "Create Routine" else "Edit Routine",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        if (onDelete != null) {
                            IconButton(onClick = { onDelete(initialRoutine.id) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Routine",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val finalName = name.trim().ifBlank { "My Routine" }
                                onSave(
                                    initialRoutine.copy(
                                        name = finalName,
                                        description = description.trim(),
                                        triggerType = triggerType,
                                        bluetoothTargetName = btName.trim(),
                                        bluetoothTargetAddress = btAddress.trim(),
                                        disconnectDelaySeconds = disconnectDelaySec,
                                        chargingSource = chargingSource,
                                        wifiSsidFilter = wifiSsid.trim(),
                                        batteryThresholdPercent = batteryThreshold,
                                        onEnterActions = onEnterActions,
                                        onExitActions = onExitActions
                                    )
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Save")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Routine Name & Summary
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Routine Name") },
                    placeholder = { Text("e.g., Car Bluetooth → Mobile Hotspot") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. IF (Trigger Card)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "IF (WHEN)",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Trigger Condition",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TriggerType.entries.forEach { option ->
                                FilterChip(
                                    selected = triggerType == option,
                                    onClick = { triggerType = option },
                                    label = { Text(option.title) }
                                )
                            }
                        }

                        Text(
                            text = triggerType.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider()

                        when (triggerType) {
                            TriggerType.BLUETOOTH_DEVICE -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Select Paired Car / Bluetooth Device",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TextButton(
                                        onClick = {
                                            pairedDevices = BluetoothHelper.getPairedDevices(context)
                                        }
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Refresh Paired")
                                    }
                                }

                                if (pairedDevices.isEmpty()) {
                                    Text(
                                        text = "No paired Bluetooth devices found (or Bluetooth permission not granted yet). You can also type your car's Bluetooth name below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        pairedDevices.forEach { dev ->
                                            val isSelected = btAddress.equals(dev.address, ignoreCase = true) ||
                                                (btAddress.isBlank() && btName.equals(dev.name, ignoreCase = true))
                                            PairedDeviceRow(
                                                device = dev,
                                                isSelected = isSelected,
                                                onClick = {
                                                    btName = dev.name
                                                    btAddress = dev.address
                                                }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = btName,
                                    onValueChange = { btName = it },
                                    label = { Text("Device Name or Substring (e.g., My Car, HandsFree)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = btAddress,
                                    onValueChange = { btAddress = it },
                                    label = { Text("Bluetooth MAC Address (optional, exact match)") },
                                    placeholder = { Text("Leave blank to match by Device Name above") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Disconnect Grace Period Selector
                                Text(
                                    text = "Disconnect Grace Period: ${disconnectDelaySec}s",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Waits ${disconnectDelaySec}s after Bluetooth disconnects before turning Hotspot OFF (prevents dropping Hotspot when cranking engine).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0, 5, 10, 15, 30, 60).forEach { sec ->
                                        FilterChip(
                                            selected = disconnectDelaySec == sec,
                                            onClick = { disconnectDelaySec = sec },
                                            label = { Text(if (sec == 0) "Instant (0s)" else "${sec}s") }
                                        )
                                    }
                                }
                            }

                            TriggerType.CHARGING_STATE -> {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChargingSourceOption.entries.forEach { src ->
                                        FilterChip(
                                            selected = chargingSource == src,
                                            onClick = { chargingSource = src },
                                            label = { Text(src.label) }
                                        )
                                    }
                                }
                            }

                            TriggerType.WIFI_STATE -> {
                                OutlinedTextField(
                                    value = wifiSsid,
                                    onValueChange = { wifiSsid = it },
                                    label = { Text("Wi-Fi SSID Filter (optional)") },
                                    placeholder = { Text("Leave blank for any Wi-Fi network") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            TriggerType.BATTERY_LOW -> {
                                Text("Trigger when battery is at or below $batteryThreshold%")
                                Slider(
                                    value = batteryThreshold.toFloat(),
                                    onValueChange = { batteryThreshold = it.roundToInt() },
                                    valueRange = 5f..50f
                                )
                            }

                            TriggerType.TIME_WINDOW -> {
                                Text(
                                    text = "Active daily between 08:00 and 18:00",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // 3. THEN (On Connected / Active Actions)
                ActionsSectionCard(
                    badgeText = "THEN (ON CONNECT / START)",
                    title = "Actions When Trigger Starts",
                    subtitle = "Executed as soon as the condition becomes active (e.g., Car Bluetooth connects)",
                    actions = onEnterActions,
                    onRemoveAction = { id ->
                        onEnterActions = onEnterActions.filterNot { it.id == id }
                    },
                    onAddClick = { showAddActionForEnter = true }
                )

                // 4. WHEN ENDED (On Disconnected / Inactive Actions)
                ActionsSectionCard(
                    badgeText = "WHEN ENDED (ON DISCONNECT)",
                    title = "Actions When Trigger Ends",
                    subtitle = "Executed when the condition stops (e.g., Car Bluetooth disconnects)",
                    actions = onExitActions,
                    onRemoveAction = { id ->
                        onExitActions = onExitActions.filterNot { it.id == id }
                    },
                    onAddClick = { showAddActionForEnter = false }
                )
            }
        }
    }

    val addingToEnter = showAddActionForEnter
    if (addingToEnter != null) {
        AddActionModal(
            isEnterPhase = addingToEnter,
            onDismiss = { showAddActionForEnter = null },
            onActionCreated = { newAction ->
                if (addingToEnter) {
                    onEnterActions = onEnterActions + newAction
                } else {
                    onExitActions = onExitActions + newAction
                }
                showAddActionForEnter = null
            }
        )
    }
}

@Composable
private fun PairedDeviceRow(
    device: PairedBluetoothDeviceInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (device.isAudioOrCarClass) Icons.Default.DirectionsCar else Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (device.isCurrentlyConnected) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "CONNECTED",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsSectionCard(
    badgeText: String,
    title: String,
    subtitle: String,
    actions: List<RoutineAction>,
    onRemoveAction: (String) -> Unit,
    onAddClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = MaterialTheme.colorScheme.onSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (actions.isEmpty()) {
                Text(
                    text = "No actions configured for this phase yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (action.type == ActionType.SET_HOTSPOT) {
                                            Icons.Default.WifiTethering
                                        } else if (action.boolValue) {
                                            Icons.Default.PlayArrow
                                        } else {
                                            Icons.Default.StopCircle
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = action.summary(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = action.type.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = { onRemoveAction(action.id) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Remove action"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onAddClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Action")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddActionModal(
    isEnterPhase: Boolean,
    onDismiss: () -> Unit,
    onActionCreated: (RoutineAction) -> Unit
) {
    var selectedType by remember { mutableStateOf(ActionType.SET_HOTSPOT) }
    var boolVal by remember { mutableStateOf(isEnterPhase) }
    var intVal by remember { mutableIntStateOf(90) }
    var strVal by remember { mutableStateOf("") }
    var secondStrVal by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEnterPhase) "Add Start / Connect Action" else "Add End / Disconnect Action",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose Action Type:",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ActionType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.title) }
                        )
                    }
                }

                HorizontalDivider()

                when (selectedType) {
                    ActionType.SET_HOTSPOT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (boolVal) "Turn Mobile Hotspot ON" else "Turn Mobile Hotspot OFF",
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(checked = boolVal, onCheckedChange = { boolVal = it })
                        }
                    }

                    ActionType.SET_WIFI -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (boolVal) "Turn Wi-Fi ON" else "Turn Wi-Fi OFF",
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(checked = boolVal, onCheckedChange = { boolVal = it })
                        }
                    }

                    ActionType.SET_MEDIA_VOLUME -> {
                        Text("Media Volume: $intVal%", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = intVal.toFloat(),
                            onValueChange = { intVal = it.roundToInt() },
                            valueRange = 0f..100f
                        )
                    }

                    ActionType.SET_RINGER_MODE -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RingerModeOption.entries.forEachIndexed { idx, mode ->
                                FilterChip(
                                    selected = intVal == idx,
                                    onClick = { intVal = idx },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }

                    ActionType.SET_DND -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (boolVal) "Enable Do Not Disturb" else "Disable Do Not Disturb",
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(checked = boolVal, onCheckedChange = { boolVal = it })
                        }
                    }

                    ActionType.KEEP_SCREEN_AWAKE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (boolVal) "Keep Screen Awake ON" else "Release Screen WakeLock",
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(checked = boolVal, onCheckedChange = { boolVal = it })
                        }
                    }

                    ActionType.LAUNCH_APP -> {
                        OutlinedTextField(
                            value = secondStrVal,
                            onValueChange = { secondStrVal = it },
                            label = { Text("App Name (e.g., Google Maps, Spotify)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = strVal,
                            onValueChange = { strVal = it },
                            label = { Text("Package Name (e.g., com.google.android.apps.maps)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Google Maps" to "com.google.android.apps.maps",
                                "Waze" to "com.waze",
                                "Spotify" to "com.spotify.music",
                                "YouTube Music" to "com.google.android.apps.youtube.music"
                            ).forEach { (label, pkg) ->
                                FilterChip(
                                    selected = strVal == pkg,
                                    onClick = {
                                        secondStrVal = label
                                        strVal = pkg
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    ActionType.SEND_NOTIFICATION -> {
                        OutlinedTextField(
                            value = strVal,
                            onValueChange = { strVal = it },
                            label = { Text("Notification Message") },
                            placeholder = { Text("e.g., Car Hotspot is now active") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onActionCreated(
                        RoutineAction(
                            type = selectedType,
                            boolValue = boolVal,
                            intValue = intVal,
                            stringValue = strVal,
                            secondaryStringValue = secondStrVal
                        )
                    )
                }
            ) {
                Text("Add Action")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
