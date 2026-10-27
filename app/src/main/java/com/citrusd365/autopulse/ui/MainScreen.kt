package com.citrusd365.autopulse.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.citrusd365.autopulse.data.RoutineRepository
import com.citrusd365.autopulse.engine.BluetoothHelper
import com.citrusd365.autopulse.engine.HotspotController
import com.citrusd365.autopulse.engine.RoutineEngine
import com.citrusd365.autopulse.model.ActionType
import com.citrusd365.autopulse.model.ExecutionLogEntry
import com.citrusd365.autopulse.model.HotspotMethod
import com.citrusd365.autopulse.model.LogSeverity
import com.citrusd365.autopulse.model.Routine
import com.citrusd365.autopulse.model.RoutineAction
import com.citrusd365.autopulse.model.TriggerType
import com.citrusd365.autopulse.service.AutomationAccessibilityService
import com.citrusd365.autopulse.service.AutomationForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repo = remember { RoutineRepository.getInstance(context) }
    val routines by repo.routines.collectAsState()
    val logs by repo.logs.collectAsState()
    val engineEnabled by repo.engineEnabled.collectAsState()
    val hotspotMethod by repo.hotspotMethod.collectAsState()
    val customTileLabel by repo.customHotspotTileLabel.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var editingRoutine by remember { mutableStateOf<Routine?>(null) }

    // Permission & Service states refreshed whenever screen resumes or service connects
    val isA11yConnected by AutomationAccessibilityService.isConnectedFlow.collectAsState()
    var hasBtPerm by remember { mutableStateOf(BluetoothHelper.hasBluetoothPermission(context)) }
    var isAccessibilityEnabledSetting by remember { mutableStateOf(AutomationAccessibilityService.isServiceEnabled(context)) }
    val isAccessibilityReady = isAccessibilityEnabledSetting || isA11yConnected
    var isBatteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var liveHotspotEnabled by remember { mutableStateOf(HotspotController.isHotspotCurrentlyEnabled(context)) }

    LaunchedEffect(isA11yConnected) {
        isAccessibilityEnabledSetting = AutomationAccessibilityService.isServiceEnabled(context)
        delay(500)
        isAccessibilityEnabledSetting = AutomationAccessibilityService.isServiceEnabled(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBtPerm = BluetoothHelper.hasBluetoothPermission(context)
                isAccessibilityEnabledSetting = AutomationAccessibilityService.isServiceEnabled(context)
                isBatteryUnrestricted = isIgnoringBatteryOptimizations(context)
                liveHotspotEnabled = HotspotController.isHotspotCurrentlyEnabled(context)
                if (engineEnabled) {
                    AutomationForegroundService.startOrUpdate(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasBtPerm = BluetoothHelper.hasBluetoothPermission(context)
        if (engineEnabled) {
            AutomationForegroundService.startOrUpdate(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AutoPulse Routines",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (engineEnabled) {
                                "Background Monitor Active"
                            } else {
                                "Background Monitor Paused"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (engineEnabled) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Switch(
                            checked = engineEnabled,
                            onCheckedChange = { enabled ->
                                repo.setEngineEnabled(enabled)
                                if (enabled) {
                                    AutomationForegroundService.startOrUpdate(context)
                                } else {
                                    AutomationForegroundService.stop(context)
                                }
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingRoutine = Routine(
                            name = "",
                            triggerType = TriggerType.BLUETOOTH_DEVICE,
                            bluetoothTargetName = "My Car",
                            disconnectDelaySeconds = 10,
                            onEnterActions = listOf(
                                RoutineAction(type = ActionType.SET_HOTSPOT, boolValue = true)
                            ),
                            onExitActions = listOf(
                                RoutineAction(type = ActionType.SET_HOTSPOT, boolValue = false)
                            )
                        )
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Routine") }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                    label = { Text("Routines") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Activity Log") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        liveHotspotEnabled = HotspotController.isHotspotCurrentlyEnabled(context)
                        selectedTab = 2
                    },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Hotspot Setup") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> RoutinesTabContent(
                    routines = routines,
                    hasBluetoothPermission = hasBtPerm,
                    isAccessibilityEnabled = isAccessibilityReady,
                    isBatteryUnrestricted = isBatteryUnrestricted,
                    onRequestBluetoothPermission = {
                        val perms = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (perms.isNotEmpty()) {
                            permissionLauncher.launch(perms.toTypedArray())
                        }
                    },
                    onOpenAccessibilitySettings = {
                        openAccessibilitySettings(context)
                    },
                    onRequestUnrestrictedBattery = {
                        requestIgnoreBatteryOptimizations(context)
                    },
                    onToggleRoutine = { id, enabled ->
                        repo.toggleRoutineEnabled(id, enabled)
                    },
                    onEditRoutine = { routine ->
                        editingRoutine = routine
                    },
                    onTestRoutine = { routine, runEnter ->
                        RoutineEngine.triggerManualRun(context, routine, runEnter)
                    }
                )

                1 -> ActivityLogTabContent(
                    logs = logs,
                    onClearLogs = { repo.clearLogs() }
                )

                2 -> HotspotSetupTabContent(
                    currentMethod = hotspotMethod,
                    customTileLabel = customTileLabel,
                    isAccessibilityReady = isAccessibilityReady,
                    liveHotspotState = liveHotspotEnabled,
                    onSelectMethod = { repo.setHotspotMethod(it) },
                    onUpdateTileLabel = { repo.setCustomHotspotTileLabel(it) },
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                    onRefreshHotspotState = {
                        liveHotspotEnabled = HotspotController.isHotspotCurrentlyEnabled(context)
                    }
                )
            }
        }
    }

    val currentEditing = editingRoutine
    if (currentEditing != null) {
        RoutineEditorDialog(
            initialRoutine = currentEditing,
            onDismiss = { editingRoutine = null },
            onSave = { updated ->
                repo.upsertRoutine(updated)
                editingRoutine = null
                AutomationForegroundService.startOrUpdate(context)
            },
            onDelete = if (routines.any { it.id == currentEditing.id }) {
                { id ->
                    repo.deleteRoutine(id)
                    editingRoutine = null
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun RoutinesTabContent(
    routines: List<Routine>,
    hasBluetoothPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    isBatteryUnrestricted: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestUnrestrictedBattery: () -> Unit,
    onToggleRoutine: (String, Boolean) -> Unit,
    onEditRoutine: (Routine) -> Unit,
    onTestRoutine: (Routine, Boolean) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ReadinessChecklistCard(
                hasBluetoothPermission = hasBluetoothPermission,
                isAccessibilityEnabled = isAccessibilityEnabled,
                isBatteryUnrestricted = isBatteryUnrestricted,
                onRequestBluetoothPermission = onRequestBluetoothPermission,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRequestUnrestrictedBattery = onRequestUnrestrictedBattery
            )
        }

        items(routines, key = { it.id }) { routine ->
            RoutineItemCard(
                routine = routine,
                onToggleEnabled = { enabled -> onToggleRoutine(routine.id, enabled) },
                onEdit = { onEditRoutine(routine) },
                onTestEnter = { onTestRoutine(routine, true) },
                onTestExit = { onTestRoutine(routine, false) }
            )
        }
    }
}

@Composable
private fun ReadinessChecklistCard(
    hasBluetoothPermission: Boolean,
    isAccessibilityEnabled: Boolean,
    isBatteryUnrestricted: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestUnrestrictedBattery: () -> Unit
) {
    val allReady = hasBluetoothPermission && isAccessibilityEnabled && isBatteryUnrestricted

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (allReady) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            }
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (allReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (allReady) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (allReady) {
                            "System Ready for Car → Hotspot Automation"
                        } else {
                            "Quick One-Time Setup Required"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (allReady) {
                            "Bluetooth detection, Accessibility Hotspot helper, and background execution are all active."
                        } else {
                            "Grant these items once so Android allows automatic Hotspot toggling when your car connects."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!allReady) {
                HorizontalDivider()

                SetupStepRow(
                    title = "1. Bluetooth Connection Permission",
                    subtitle = "Detects when your Car Bluetooth connects & disconnects",
                    isDone = hasBluetoothPermission,
                    actionLabel = "Grant",
                    onClick = onRequestBluetoothPermission
                )

                SetupStepRow(
                    title = "2. Accessibility Hotspot Helper",
                    subtitle = "Toggles the Mobile Hotspot Quick Settings tile without needing Root or PC",
                    isDone = isAccessibilityEnabled,
                    actionLabel = "Enable",
                    onClick = onOpenAccessibilitySettings
                )

                SetupStepRow(
                    title = "3. Unrestricted Background Battery",
                    subtitle = "Prevents phone sleep from delaying Bluetooth detection while in your pocket",
                    isDone = isBatteryUnrestricted,
                    actionLabel = "Allow",
                    onClick = onRequestUnrestrictedBattery
                )
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    title: String,
    subtitle: String,
    isDone: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isDone) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Ready",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            FilledTonalButton(onClick = onClick) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineItemCard(
    routine: Routine,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTestEnter: () -> Unit,
    onTestExit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        border = if (routine.isCurrentlyActive && routine.enabled) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (routine.triggerType) {
                            TriggerType.BLUETOOTH_DEVICE -> Icons.Default.Bluetooth
                            TriggerType.CHARGING_STATE -> Icons.Default.BatteryChargingFull
                            else -> Icons.Default.WifiTethering
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = routine.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (routine.isCurrentlyActive && routine.enabled) {
                            Text(
                                text = "● ACTIVE NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Switch(
                    checked = routine.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            // IF section
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "IF: ${routine.triggerSummary()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (routine.onEnterActions.isNotEmpty()) {
                        Text(
                            text = "THEN: " + routine.onEnterActions.joinToString(" • ") { it.summary() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (routine.onExitActions.isNotEmpty()) {
                        val delayNote = if (routine.triggerType == TriggerType.BLUETOOTH_DEVICE && routine.disconnectDelaySeconds > 0) {
                            " (after ${routine.disconnectDelaySeconds}s)"
                        } else {
                            ""
                        }
                        Text(
                            text = "WHEN DISCONNECTED$delayNote: " +
                                routine.onExitActions.joinToString(" • ") { it.summary() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick Actions Row: Edit / Test Connect / Test Disconnect
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Configure Device & Actions")
                }
                FilledTonalButton(onClick = onTestEnter) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test ON")
                }
                FilledTonalButton(onClick = onTestExit) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test OFF")
                }
            }
        }
    }
}

@Composable
private fun ActivityLogTabContent(
    logs: List<ExecutionLogEntry>,
    onClearLogs: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Execution History (${logs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (logs.isNotEmpty()) {
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs")
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No events logged yet. Tap 'Test ON' on a routine or connect to your car Bluetooth.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { entry ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when (entry.severity) {
                                        LogSeverity.SUCCESS -> MaterialTheme.colorScheme.secondary
                                        LogSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                                        LogSeverity.ERROR -> MaterialTheme.colorScheme.error
                                        LogSeverity.INFO -> MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = dateFormatter.format(Date(entry.timestampEpochMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Routine: ${entry.routineName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = entry.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotspotSetupTabContent(
    currentMethod: HotspotMethod,
    customTileLabel: String,
    isAccessibilityReady: Boolean,
    liveHotspotState: Boolean?,
    onSelectMethod: (HotspotMethod) -> Unit,
    onUpdateTileLabel: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRefreshHotspotState: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var labelInput by remember(customTileLabel) { mutableStateOf(customTileLabel) }
    var testResultBanner by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WifiTethering,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Live Mobile Hotspot Tester",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Current state: " + when (liveHotspotState) {
                                        true -> "ACTIVE (ON)"
                                        false -> "INACTIVE (OFF)"
                                        null -> "Unknown (Verified via Quick Settings)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    testResultBanner = "Testing Hotspot ON..."
                                    val (_, msg) = HotspotController.setHotspotEnabled(context, true)
                                    testResultBanner = msg
                                    onRefreshHotspotState()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Turn Hotspot ON")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    testResultBanner = "Testing Hotspot OFF..."
                                    val (_, msg) = HotspotController.setHotspotEnabled(context, false)
                                    testResultBanner = msg
                                    onRefreshHotspotState()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Turn Hotspot OFF")
                        }
                    }

                    if (testResultBanner != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = testResultBanner.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Hotspot Toggling Backend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Android 10+ blocks standard apps from calling startTethering() directly. AutoPulse solves this without root by checking current Hotspot state first and using Accessibility to toggle your Quick Settings tile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HotspotMethod.entries.forEach { method ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMethod(method) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = currentMethod == method,
                                onClick = { onSelectMethod(method) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = method.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = method.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = {
                            labelInput = it
                            onUpdateTileLabel(it)
                        },
                        label = { Text("Quick Settings Tile Label") },
                        placeholder = { Text("Hotspot") },
                        supportingText = {
                            Text("Tip: Make sure the 'Hotspot' tile is placed in the top 4-6 tiles of your phone's Quick Settings shade.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isAccessibilityReady) {
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AccessibilityNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enable Accessibility Helper in Android Settings")
                        }
                    }
                }
            }
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}
