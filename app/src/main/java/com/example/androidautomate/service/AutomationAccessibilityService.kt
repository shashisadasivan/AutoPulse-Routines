package com.example.androidautomate.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.example.androidautomate.data.RoutineRepository
import com.example.androidautomate.engine.HotspotController
import com.example.androidautomate.model.LogSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutomationAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnectedFlow.value = true
        RoutineRepository.getInstance(applicationContext).appendLog(
            routineName = "System",
            title = "Accessibility Helper Connected",
            details = "Quick Settings Hotspot auto-toggle service is active and ready.",
            severity = LogSeverity.SUCCESS
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // On-demand node inspection is performed when a routine triggers a Hotspot toggle
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Opens Quick Settings, locates the Hotspot tile in SystemUI, verifies its current ON/OFF state,
     * clicks the tile (plus any confirmation dialog), and closes the shade.
     */
    fun performQuickSettingsHotspotToggle(
        targetEnabled: Boolean,
        customTileLabel: String,
        useSettingsScreenFallback: Boolean = true,
        onResult: (Boolean, String) -> Unit
    ) {
        serviceScope.launch {
            val wakeLock = acquireBriefScreenWakeLock()
            try {
                val labelsToMatch = buildList {
                    if (customTileLabel.isNotBlank()) add(customTileLabel.trim())
                    addAll(DEFAULT_HOTSPOT_LABELS)
                }.distinct()

                // Step 1: Open Quick Settings panel
                val openedQs = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                if (!openedQs) {
                    if (useSettingsScreenFallback) {
                        val fallback = toggleViaTetherSettingsActivity(targetEnabled)
                        onResult(fallback.first, fallback.second)
                    } else {
                        onResult(false, "Could not open Quick Settings shade.")
                    }
                    return@launch
                }

                delay(650)

                // Step 2: Search SystemUI windows for the Hotspot Quick Settings tile
                var tileMatch = findHotspotTileNode(labelsToMatch)

                // Step 3: If not visible in the compact header, swipe down to expand the full Quick Settings grid
                if (tileMatch == null) {
                    performSwipeGesture(startX = 540f, startY = 350f, endX = 540f, endY = 1450f, durationMs = 280L)
                    delay(650)
                    tileMatch = findHotspotTileNode(labelsToMatch)
                }

                if (tileMatch != null) {
                    val (clickableNode, tileDetectedState, debugDesc) = tileMatch
                    val systemDetectedState = HotspotController.isHotspotCurrentlyEnabled(applicationContext)
                    val currentState = tileDetectedState ?: systemDetectedState

                    if (currentState != null && currentState == targetEnabled) {
                        dismissQuickSettingsShade()
                        onResult(
                            true,
                            "Hotspot is already ${if (targetEnabled) "ON" else "OFF"} ($debugDesc)."
                        )
                        return@launch
                    }

                    // Click the Quick Settings tile (preferring the tile container over child text label)
                    val clicked = clickTileNodeReliably(clickableNode)
                    delay(750)

                    // Check if a secondary detail switch or confirmation dialog ("Turn on" / "OK") popped up
                    handlePostTileClickDialogOrDetailSwitch(targetEnabled)

                    delay(600)
                    dismissQuickSettingsShade()

                    if (clicked) {
                        onResult(
                            true,
                            "Toggled Hotspot ${if (targetEnabled) "ON" else "OFF"} via Quick Settings tile ($debugDesc)."
                        )
                    } else {
                        onResult(
                            false,
                            "Located Hotspot tile ($debugDesc), but Android rejected the click action."
                        )
                    }
                    return@launch
                }

                // Close Quick Settings before falling back to Tethering Settings screen
                dismissQuickSettingsShade()
                delay(450)

                if (useSettingsScreenFallback) {
                    val fallbackResult = toggleViaTetherSettingsActivity(targetEnabled)
                    onResult(fallbackResult.first, fallbackResult.second)
                } else {
                    onResult(
                        false,
                        "Hotspot tile not found in Quick Settings. Place the 'Hotspot' tile in the top 4–6 tiles of your Quick Settings panel."
                    )
                }
            } catch (t: Throwable) {
                onResult(false, "Accessibility toggle error: ${t.localizedMessage ?: t.javaClass.simpleName}")
            } finally {
                releaseWakeLockSafely(wakeLock)
            }
        }
    }

    /**
     * Opens the system Tethering Settings screen directly and flips the Wi-Fi Hotspot switch.
     */
    fun performSettingsScreenHotspotToggle(
        targetEnabled: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        serviceScope.launch {
            val wakeLock = acquireBriefScreenWakeLock()
            try {
                val res = toggleViaTetherSettingsActivity(targetEnabled)
                onResult(res.first, res.second)
            } catch (t: Throwable) {
                onResult(false, "Settings toggle error: ${t.localizedMessage ?: t.javaClass.simpleName}")
            } finally {
                releaseWakeLockSafely(wakeLock)
            }
        }
    }

    /**
     * Handles OEM confirmation popups ("Turn off Wi-Fi? ... [Turn on] / [OK]") or secondary detail Switch panels.
     */
    private suspend fun handlePostTileClickDialogOrDetailSwitch(targetEnabled: Boolean) {
        val roots = getSystemUiAndSettingsRoots()
        for (root in roots) {
            if (targetEnabled) {
                val confirmBtn = findDialogConfirmationButton(root)
                if (confirmBtn != null) {
                    clickNodeOrTapBounds(confirmBtn)
                    delay(500)
                    return
                }
            }
            val detailSwitch = findDetailPanelSwitch(root)
            if (detailSwitch != null) {
                val switchState = extractNodeEnabledState(detailSwitch)
                if (switchState != null && switchState != targetEnabled) {
                    clickNodeOrTapBounds(detailSwitch)
                    delay(500)
                    return
                }
            }
        }
    }

    private fun findDialogConfirmationButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val confirmLabels = listOf("turn on", "ok", "yes", "allow", "enable", "continue")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            visited++
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()

            val isDialogButton = cls.contains("Button", ignoreCase = true) ||
                viewId.contains("button1", ignoreCase = true)
            if (isDialogButton && confirmLabels.any { text.equals(it, ignoreCase = true) }) {
                return findClickableSelfOrAncestor(node) ?: node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findDetailPanelSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            visited++
            val node = queue.removeFirst()
            val cls = node.className?.toString().orEmpty()
            if ((cls.contains("Switch", ignoreCase = true) || cls.contains("ToggleButton", ignoreCase = true)) &&
                node.isCheckable
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private suspend fun toggleViaTetherSettingsActivity(targetEnabled: Boolean): Pair<Boolean, String> {
        val launched = launchTetherSettingsScreen()
        if (!launched) {
            return false to "Could not launch system Hotspot & Tethering settings screen."
        }
        delay(1000)

        // Step 1: Check if we are on the parent "Hotspot & tethering" menu or already on the "Wi-Fi hotspot" sub-screen
        val roots = getSettingsRoots()
        for (root in roots) {
            // Look specifically for the "Wi-Fi hotspot" / "Mobile Hotspot" row first
            val hotspotRow = findHotspotRowInSettings(root)
            val mainBarSwitch = findMainBarSwitchInSettings(root)

            if (mainBarSwitch != null) {
                // We are already on the Wi-Fi Hotspot detail sub-screen (which has a top main Switch bar)
                val result = toggleSwitchNodeIfNeeded(mainBarSwitch, targetEnabled)
                returnToAppFromSettings()
                return result
            }

            if (hotspotRow != null) {
                // Check if this row has an inline Switch inside it
                val inlineSwitch = findDetailPanelSwitch(hotspotRow)
                if (inlineSwitch != null) {
                    val result = toggleSwitchNodeIfNeeded(inlineSwitch, targetEnabled)
                    returnToAppFromSettings()
                    return result
                }

                // Otherwise, tap the "Wi-Fi hotspot" row to open its sub-screen
                clickNodeOrTapBounds(hotspotRow)
                delay(900)

                val subScreenRoots = getSettingsRoots()
                for (subRoot in subScreenRoots) {
                    val subSwitch = findMainBarSwitchInSettings(subRoot) ?: findDetailPanelSwitch(subRoot)
                    if (subSwitch != null) {
                        val result = toggleSwitchNodeIfNeeded(subSwitch, targetEnabled)
                        returnToAppFromSettings()
                        return result
                    }
                }
            }
        }

        returnToAppFromSettings()
        return false to "Could not locate Wi-Fi Hotspot switch in Settings. Place the 'Hotspot' tile in the top of your Quick Settings shade for 1-step toggling."
    }

    private suspend fun toggleSwitchNodeIfNeeded(
        switchNode: AccessibilityNodeInfo,
        targetEnabled: Boolean
    ): Pair<Boolean, String> {
        val currentState = extractNodeEnabledState(switchNode)
        if (currentState != null && currentState == targetEnabled) {
            return true to "Hotspot is already ${if (targetEnabled) "ON" else "OFF"} in Tethering Settings."
        }
        val clicked = clickNodeOrTapBounds(switchNode)
        delay(650)
        // Handle any "Turn off Wi-Fi?" confirmation dialog in Settings
        val roots = getSettingsRoots()
        for (r in roots) {
            val confirmBtn = findDialogConfirmationButton(r)
            if (confirmBtn != null) {
                clickNodeOrTapBounds(confirmBtn)
                delay(500)
                break
            }
        }
        return if (clicked) {
            true to "Toggled Hotspot ${if (targetEnabled) "ON" else "OFF"} via Tethering Settings."
        } else {
            false to "Found Hotspot switch in Tethering Settings, but click was rejected."
        }
    }

    /**
     * Safely backs out of the Android Settings screen ONLY while Settings is in the foreground,
     * so we never accidentally press Back on our own app (`MainActivity`) and close it.
     */
    private suspend fun returnToAppFromSettings() {
        repeat(3) {
            val activePkg = runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }.getOrDefault("")
            if (activePkg.equals(applicationContext.packageName, ignoreCase = true) ||
                !activePkg.contains("settings", ignoreCase = true)
            ) {
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(400)
        }
    }

    private fun launchTetherSettingsScreen(): Boolean {
        // Use standard public Intent actions first to avoid fragment crashes on SubSettings
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.settings.WIFI_AP_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        for (intent in intents) {
            val ok = runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun dismissQuickSettingsShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dismissed = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            if (dismissed) return
        }
        val activePkg = runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }.getOrDefault("")
        if (activePkg.contains("systemui", ignoreCase = true)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun getSystemUiAndSettingsRoots(): List<AccessibilityNodeInfo> {
        val ownPkg = applicationContext.packageName
        val result = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg != ownPkg && isSystemUiOrSettingsPackage(pkg)) {
                    result.add(root)
                }
            }
        }
        runCatching {
            rootInActiveWindow?.let { activeRoot ->
                val pkg = activeRoot.packageName?.toString().orEmpty()
                if (pkg != ownPkg && isSystemUiOrSettingsPackage(pkg) && result.none { it == activeRoot }) {
                    result.add(0, activeRoot)
                }
            }
        }
        return result
    }

    private fun getSettingsRoots(): List<AccessibilityNodeInfo> {
        return getSystemUiAndSettingsRoots().filter { root ->
            val pkg = root.packageName?.toString().orEmpty().lowercase()
            pkg.contains("settings") || pkg.contains("tether")
        }
    }

    private fun isSystemUiOrSettingsPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg.equals(applicationContext.packageName, ignoreCase = true)) return false
        val lower = pkg.lowercase()
        return lower.contains("systemui") ||
            lower == "com.android.settings" ||
            lower.endsWith(".settings") ||
            lower.contains("tether")
    }

    /**
     * Searches all SystemUI windows for a Quick Settings tile matching Hotspot labels.
     * Returns Triple(NodeToClick, DetectedBooleanStateOrNull, DebugSummary).
     */
    private fun findHotspotTileNode(labels: List<String>): Triple<AccessibilityNodeInfo, Boolean?, String>? {
        val roots = getSystemUiAndSettingsRoots()
        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (!pkg.contains("systemui", ignoreCase = true)) continue
            val match = searchNodeTreeForHotspotTile(root, labels)
            if (match != null) return match
        }
        return null
    }

    private fun searchNodeTreeForHotspotTile(
        node: AccessibilityNodeInfo?,
        labels: List<String>
    ): Triple<AccessibilityNodeInfo, Boolean?, String>? {
        if (node == null) return null

        // Only skip actual individual notification cards (`ExpandableNotificationRow`),
        // NEVER skip `notification_panel` which is the parent container of Quick Settings!
        val viewId = node.viewIdResourceName.orEmpty().lowercase()
        val cls = node.className?.toString().orEmpty().lowercase()
        if (cls.contains("expandablenotificationrow") ||
            viewId.endsWith("expandablenotificationrow") ||
            viewId.endsWith("status_bar_latest_event_content") ||
            viewId.endsWith("notification_stack_scroller")
        ) {
            return null
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

        val matchesLabel = labels.any { label ->
            matchesTileLabel(text, label) || matchesTileLabel(contentDesc, label)
        }

        if (matchesLabel && !isInsideNotificationCard(node)) {
            val tileContainer = findBestTileContainerToClick(node)
            val state = extractNodeEnabledState(tileContainer) ?: extractNodeEnabledState(node)
            val debugInfo = "tile='${text.ifBlank { contentDesc }}', state=${state ?: "unknown"}"
            return Triple(tileContainer, state, debugInfo)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeTreeForHotspotTile(child, labels)
            if (found != null) return found
        }
        return null
    }

    private fun matchesTileLabel(candidate: String, label: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.equals(label, ignoreCase = true)) return true
        val segments = candidate.split(',', '\n', '•', '|', '-').map { it.trim() }
        if (segments.any { it.equals(label, ignoreCase = true) }) return true
        return candidate.contains(label, ignoreCase = true) && candidate.length <= 50
    }

    private fun isInsideNotificationCard(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            val id = current.viewIdResourceName.orEmpty().lowercase()
            val cls = current.className?.toString().orEmpty().lowercase()
            if (cls.contains("expandablenotificationrow") ||
                id.endsWith("status_bar_latest_event_content") ||
                id.endsWith("notification_stack_scroller")
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findBestTileContainerToClick(matchedNode: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = matchedNode
        var bestClickable: AccessibilityNodeInfo? = if (matchedNode.isClickable) matchedNode else null
        var depth = 0

        while (current != null && depth < 6) {
            val cls = current.className?.toString().orEmpty()
            val viewId = current.viewIdResourceName.orEmpty()
            if (current.isClickable) {
                bestClickable = current
                if (current.isCheckable ||
                    cls.contains("Tile", ignoreCase = true) ||
                    cls.contains("Switch", ignoreCase = true) ||
                    cls.contains("Button", ignoreCase = true) ||
                    viewId.contains("tile", ignoreCase = true)
                ) {
                    return current
                }
            }
            current = current.parent
            depth++
        }
        return bestClickable ?: matchedNode
    }

    /**
     * Finds the top main switch bar on the `Wi-Fi hotspot` sub-screen (`Use Wi-Fi hotspot` / `On` / `Off`).
     */
    private fun findMainBarSwitchInSettings(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            visited++
            val current = queue.removeFirst()
            val viewId = current.viewIdResourceName.orEmpty().lowercase()
            val text = current.text?.toString()?.trim().orEmpty()
            val desc = current.contentDescription?.toString()?.trim().orEmpty()

            val isMainBar = viewId.contains("switch_bar") ||
                viewId.contains("main_switch") ||
                text.contains("Use Wi-Fi hotspot", ignoreCase = true) ||
                desc.contains("Use Wi-Fi hotspot", ignoreCase = true)

            if (isMainBar) {
                if (current.isCheckable) return current
                val childSwitch = findDetailPanelSwitch(current)
                if (childSwitch != null) return childSwitch
                return findClickableSelfOrAncestor(current) ?: current
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Finds the "Wi-Fi hotspot" / "Mobile Hotspot" preference row on the "Hotspot & tethering" Settings screen,
     * never confusing it with USB or Bluetooth tethering switches.
     */
    private fun findHotspotRowInSettings(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            visited++
            val current = queue.removeFirst()
            val text = current.text?.toString()?.trim().orEmpty()
            val desc = current.contentDescription?.toString()?.trim().orEmpty()

            val isHotspotTitle = DEFAULT_HOTSPOT_LABELS.any { label ->
                text.equals(label, ignoreCase = true) || desc.equals(label, ignoreCase = true)
            }
            if (isHotspotTitle) {
                return findClickableSelfOrAncestor(current) ?: current
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Accurately extracts whether a Quick Settings tile or Switch is currently ON (`true`) or OFF (`false`).
     */
    private fun extractNodeEnabledState(node: AccessibilityNodeInfo): Boolean? {
        if (node.isCheckable) {
            return node.isChecked
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val stateDesc = node.stateDescription?.toString()?.trim().orEmpty()
            if (stateDesc.isNotBlank()) {
                val parsed = parseStrictStateToken(stateDesc)
                if (parsed != null) return parsed
            }
        }

        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
        if (contentDesc.isNotBlank()) {
            val parsed = parseStrictStateToken(contentDesc)
            if (parsed != null) return parsed
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isCheckable) return child.isChecked
            val childState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                child.stateDescription?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val childText = child.text?.toString()?.trim().orEmpty()
            parseStrictStateToken(childState)?.let { return it }
            if (childText.equals("On", ignoreCase = true) || childText.equals("Active", ignoreCase = true)) return true
            if (childText.equals("Off", ignoreCase = true) || childText.equals("Inactive", ignoreCase = true)) return false
        }

        return null
    }

    private fun parseStrictStateToken(raw: String): Boolean? {
        if (raw.isBlank()) return null
        val cleaned = raw.lowercase()
            .replace("double tap to turn on", "")
            .replace("tap to turn on", "")
            .replace("double tap to turn off", "")
            .replace("tap to turn off", "")
            .replace("double tap to activate", "")
            .replace("tap to activate", "")
            .trim()

        val segments = cleaned.split(',', '\n', '|', '•', ';').map { it.trim() }.filter { it.isNotEmpty() }
        for (seg in segments) {
            if (seg == "off" || seg == "inactive" || seg == "disabled" || seg == "not sharing" || seg == "unavailable") {
                return false
            }
            if (seg == "on" || seg == "active" || seg == "enabled" || seg == "sharing") {
                return true
            }
        }
        return null
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private suspend fun clickTileNodeReliably(node: AccessibilityNodeInfo): Boolean {
        val target = findBestTileContainerToClick(node)
        val actionClicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (actionClicked) return true
        return tapNodeBounds(target)
    }

    private suspend fun clickNodeOrTapBounds(node: AccessibilityNodeInfo): Boolean {
        val target = findClickableSelfOrAncestor(node) ?: node
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapNodeBounds(target)
    }

    private suspend fun tapNodeBounds(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.centerX() <= 0 || rect.centerY() <= 0) return false
        return performTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private suspend fun performTapGesture(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
            }.getOrDefault(false)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }

    private suspend fun performSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
        }.getOrDefault(false)
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireBriefScreenWakeLock(): PowerManager.WakeLock? {
        return runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AutoPulse:HotspotToggleWakeLock"
            ).apply {
                acquire(10_000L)
            }
        }.getOrNull()
    }

    private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    companion object {
        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        @Volatile
        private var lastAutoBindAttemptMs: Long = 0L

        private val DEFAULT_HOTSPOT_LABELS = listOf(
            "Hotspot",
            "Mobile Hotspot",
            "Wi-Fi hotspot",
            "Personal hotspot",
            "Tethering"
        )

        fun hasWriteSecureSettingsPermission(context: Context): Boolean {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }

        /**
         * If `WRITE_SECURE_SETTINGS` has been granted (e.g. via ADB or Android Studio),
         * automatically re-enables and binds `AutomationAccessibilityService` whenever Android
         * or an IDE force-stop turns it off.
         */
        fun tryAutoEnableIfPermitted(context: Context): Boolean {
            if (!hasWriteSecureSettingsPermission(context)) return false
            return runCatching {
                val cr = context.contentResolver
                val fullComponent = "${context.packageName}/${AutomationAccessibilityService::class.java.canonicalName}"
                val currentRaw = Settings.Secure.getString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()

                val isAlreadyListed = currentRaw.split(':').any {
                    it.contains(context.packageName, ignoreCase = true) &&
                        it.contains(AutomationAccessibilityService::class.java.simpleName, ignoreCase = true)
                }

                if (isAlreadyListed && instance != null) {
                    return true
                }

                val now = System.currentTimeMillis()
                if (isAlreadyListed && instance == null && (now - lastAutoBindAttemptMs) < 8_000L) {
                    return true
                }
                lastAutoBindAttemptMs = now

                val otherEntries = currentRaw.split(':')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains(context.packageName, ignoreCase = true) }

                // If listed in Settings but unbound by Android after an app update/stop,
                // briefly write without our component first so AccessibilityManagerService re-binds it.
                if (isAlreadyListed && instance == null) {
                    Settings.Secure.putString(
                        cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        otherEntries.joinToString(":")
                    )
                }

                val updatedEntries = (otherEntries + fullComponent).joinToString(":")
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updatedEntries
                )
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.getOrDefault(false)
        }

        suspend fun ensureServiceBound(context: Context): AutomationAccessibilityService? {
            instance?.let { return it }
            tryAutoEnableIfPermitted(context)
            repeat(15) {
                instance?.let { return it }
                delay(100)
            }
            return instance
        }

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true

            // Automatically self-heal if WRITE_SECURE_SETTINGS is granted
            tryAutoEnableIfPermitted(context)
            if (instance != null) return true

            // 1. Check via official AccessibilityManager API
            runCatching {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
                val foundInAm = enabledList.any { info ->
                    val svc = info.resolveInfo?.serviceInfo
                    val id = info.id.orEmpty()
                    (svc?.packageName.equals(context.packageName, ignoreCase = true) &&
                        svc?.name?.contains(AutomationAccessibilityService::class.java.simpleName, ignoreCase = true) == true) ||
                        (id.contains(context.packageName, ignoreCase = true) &&
                            id.contains(AutomationAccessibilityService::class.java.simpleName, ignoreCase = true))
                }
                if (foundInAm) return true
            }

            // 2. Check via Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val fullComponent = "${context.packageName}/${AutomationAccessibilityService::class.java.canonicalName}"
            val shortComponent = "${context.packageName}/.service.${AutomationAccessibilityService::class.java.simpleName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any {
                it.equals(fullComponent, ignoreCase = true) ||
                    it.equals(shortComponent, ignoreCase = true) ||
                    (it.contains(context.packageName, ignoreCase = true) &&
                        it.contains(AutomationAccessibilityService::class.java.simpleName, ignoreCase = true))
            }
        }
    }
}
