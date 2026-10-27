# AutoPulse Routines (`AndroidAutomate`)

An open-source, Material 3 (Jetpack Compose) Android event-automation application built around a clean **"IF → THEN → WHEN ENDED"** single-card routine model.

Pre-configured out of the box for automatic in-car connectivity:
> **IF** Car Bluetooth connects → **THEN** Turn Mobile Hotspot ON (+ set Media Volume)
> **WHEN DISCONNECTED** (after configurable grace period) → Turn Mobile Hotspot OFF

---

## Key Features & Architecture

1. **Intuitive Single-Card Routine Model**
   - Combines **IF (Trigger)**, **THEN (On Enter Actions)**, and **WHEN ENDED (On Exit Actions + Disconnect Grace Period)** into a single unified routine card.
2. **Android 12–15 Foreground Service & Permission Compliance**
   - Uses `foregroundServiceType="connectedDevice|specialUse"` with runtime checks for `BLUETOOTH_CONNECT` so Android 14/15 never throws a `SecurityException` or `ForegroundServiceStartNotAllowedException`.
3. **Reliable Unrooted Mobile Hotspot Toggling on Android 10–15**
   - Android 10+ restricts `ConnectivityManager.startTethering()` to privileged apps.
   - AutoPulse uses a multi-layered approach (`HotspotController` + `AutomationAccessibilityService`):
     1. **Idempotent State Verification**: Checks whether Wi-Fi Hotspot (SoftAP) is *already* ON or OFF via `WifiManager` AP state inspection, active network interface (`ap0`/`softap0`/`swlan0`) checks, and Quick Settings tile `isChecked` / `stateDescription` inspection — preventing accidental "blind toggles".
     2. **Quick Settings Tile Auto-Toggler (`AccessibilityService`)**: Briefly wakes the screen if needed, opens Quick Settings, locates the `"Hotspot"` tile, clicks it only if a state transition is needed, and immediately dismisses the Quick Settings shade.
     3. **Tethering Settings Screen & Root Fallbacks**: Falls back to toggling the switch in the system Tethering Settings screen if the tile isn't in Quick Settings, or `su -c cmd connectivity start-tethering wifi` on rooted devices.
4. **Disconnect Grace Period (`disconnectDelaySeconds`)**
   - Prevents brief Bluetooth disconnects (e.g., starting the car engine after accessory mode) from turning off your Hotspot if the car reconnects within the grace window (default `10s`).
5. **100% Offline & Privacy-Respecting**
   - Requests zero internet permissions (`INTERNET` is not used), contains no proprietary SDKs, no trackers, and no analytics.

---

## Project Structure

- [`RoutineModels.kt`](app/src/main/java/com/example/androidautomate/model/RoutineModels.kt) — Serializable domain models for `Routine`, `TriggerType`, `ActionType`, `RoutineAction`, `HotspotMethod`, and `ExecutionLogEntry`.
- [`RoutineRepository.kt`](app/src/main/java/com/example/androidautomate/data/RoutineRepository.kt) — Persistent JSON storage & reactive `StateFlow`s with default **Car Bluetooth → Mobile Hotspot** routine.
- [`AutomationAccessibilityService.kt`](app/src/main/java/com/example/androidautomate/service/AutomationAccessibilityService.kt) — Stock-Android unrooted Hotspot toggler via Quick Settings tile & Tethering Settings inspection.
- [`HotspotController.kt`](app/src/main/java/com/example/androidautomate/engine/HotspotController.kt) — Multi-backend Hotspot state detector (`WifiManager` + SoftAP interfaces) and toggler (`AUTO`, `ACCESSIBILITY_QUICK_TILE`, `ACCESSIBILITY_SETTINGS_SCREEN`, `ROOT_SHELL`).
- [`BluetoothHelper.kt`](app/src/main/java/com/example/androidautomate/engine/BluetoothHelper.kt) — Safe Android 12+ `BLUETOOTH_CONNECT` paired-device query, Car/Audio class detection, and live connection state check.
- [`RoutineEngine.kt`](app/src/main/java/com/example/androidautomate/engine/RoutineEngine.kt) — Event evaluator, disconnect grace-period timer manager, and action executor.
- [`AutomationForegroundService.kt`](app/src/main/java/com/example/androidautomate/service/AutomationForegroundService.kt) & [`SystemEventReceiver.kt`](app/src/main/java/com/example/androidautomate/receiver/SystemEventReceiver.kt) — Persistent background Bluetooth (`ACL_CONNECTED`, `ACL_DISCONNECTED`, `A2DP`, `HEADSET`), Charging, Wi-Fi, and Battery monitor.
- [`MainScreen.kt`](app/src/main/java/com/example/androidautomate/ui/MainScreen.kt) & [`RoutineEditorDialog.kt`](app/src/main/java/com/example/androidautomate/ui/RoutineEditorDialog.kt) — Material 3 Compose UI with Readiness Checklist, Paired Car Bluetooth Picker, Live Hotspot Tester, and Activity Log.

---

## How to Build & Install

1. Open the project in **Android Studio** (Ladybug or newer) or build from the command line via `./gradlew assembleDebug` (or `./gradlew assembleRelease`).
2. Connect your Android phone via USB (or Wireless Debugging) and click **Run (`Shift+F10`)**.
3. On the phone:
   - Complete the 3 one-tap items in the **Quick One-Time Setup** card on the Home screen (**Grant Bluetooth**, **Enable Accessibility Hotspot Helper**, **Allow Unrestricted Battery**).
   - Ensure the **Hotspot** tile is placed in the top 4–6 tiles of your phone's Quick Settings shade.
   - Tap **Configure Device & Actions** on the **Car Bluetooth → Mobile Hotspot** card and select your car from the **Paired Bluetooth Devices** list.

---

## F-Droid Readiness & Packaging

This repository is structured to meet all [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) requirements:
- **Open-Source License**: Licensed under [`GPL-3.0-or-later`](LICENSE).
- **No Proprietary Dependencies or Binary Blobs**: Built strictly with standard AndroidX/Jetpack Compose and Kotlinx libraries from Maven Central and Google Maven; Google Play `dependenciesInfo` signing blocks are explicitly disabled in [`app/build.gradle.kts`](app/build.gradle.kts).
- **Fastlane Store Metadata**: Pre-populated in [`fastlane/metadata/android/en-US/`](fastlane/metadata/android/en-US/) (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/1.txt`, and `images/icon.png`).
- **F-Droid Build Recipe**: A ready-to-submit `fdroiddata` recipe is included at [`fdroid/com.example.androidautomate.yml`](fdroid/com.example.androidautomate.yml).

---

## License

Licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE).
