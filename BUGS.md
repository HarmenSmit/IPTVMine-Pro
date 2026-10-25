# Project Bug Analysis & Tracking Registry

This document records the results of a detailed static code analysis and security/compatibility review for the IPTVMine Pro project. It outlines potential bug vectors, architectural risks, and missing features/TODOs, and provides a structured registry for tracking active issues.

---

## 📊 Project Health Overview

- **Build Status**: 🟢 **SUCCESSFUL**
  - Checked via local Gradle compilation (`.\gradlew.bat compileDebugKotlin`). The project compiles successfully with zero compile-time Kotlin errors.
- **Min SDK Version**: `24` (Android 7.0)
- **Target SDK Version**: `36`
- **Core Architectures**: MVVM, Jetpack Compose, Kotlin Coroutines, AndroidX Media3 (ExoPlayer), Headless WebView JS Bridge.

---

## 🔍 Detailed Code Analysis & Potential Bug Vectors

During the codebase review, several architectural risks, potential memory leaks, and runtime compatibility issues were identified:

### 1. ExoPlayer/Media3 & PiP Lifecycle Risks
*File location: [PlayerActivity.kt](file:///c:/Users/ADMIN/AndroidStudioProjects/IPTVMine-Pro/Player/src/main/java/com/samyak/player/PlayerActivity.kt)*

* **API Compatibility Crash in Broadcast Registration**:
  On line 1265, `registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)` is called when Picture-in-Picture receiver is registered.
  * **Risk**: The `RECEIVER_NOT_EXPORTED` flag was introduced in API level 33 (Android 13). Since the app's `minSdk` is `24`, calling this on devices running Android 8.0 to Android 12 (API levels 26–32) will result in a runtime exception (`NoSuchMethodError` or `IllegalArgumentException`), crashing the player activity when initiating PiP.
  * **Remedy**: Wrap with an SDK check or use `ContextCompat.registerReceiver` from the AndroidX Core library, which automatically manages receiver exporting flags on compatible OS versions:
    ```kotlin
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(pipReceiver, filter)
    }
    ```

* **Leaked Suspended Coroutines on WebView Destruction**:
  *File location: [VegaProviderRunner.kt](file:///c:/Users/ADMIN/AndroidStudioProjects/IPTVMine-Pro/app/src/main/java/com/samyak/iptvminepro/provider/VegaProviderRunner.kt)*
  * **Risk**: The runner evaluates JS asynchronously via `evalJsAsync` and suspends waiting for `deferred.await()` with a 45-second timeout. If the runner's `destroy()` method is called while a JS scraper request is actively pending, the WebView is destroyed, but the suspended coroutines in `evalJsAsync` are left waiting for the full 45-second timeout before throwing.
  * **Remedy**: Introduce a `clearPendingCallbacks` or cancel method inside `AndroidBridge` to immediately cancel all active Deferred promises in the callback map upon runner destruction.

---

## 🛠 Missing Features & Functional Gaps (TODOs)

Several files contain placeholders and unimplemented functions that are currently marked with `TODO`:

### 1. Settings Menu Navigation
*File location: [SettingsScreen.kt](file:///c:/Users/ADMIN/AndroidStudioProjects/IPTVMine-Pro/app/src/main/java/com/samyak/iptvminepro/ui/screens/SettingsScreen.kt)*

* **Bug Report Option**:
  * **Impact**: Navigates to `BugReportScreen` to submit bug details.
* **Legal and Disclaimers**:
  ```kotlin
  // Privacy Policy, Terms & Conditions, Disclaimer
  onClick = { /* TODO */ }
  ```
  * **Impact**: Unimplemented links for app legal requirements.

### 2. Network State Adaptability
* **ExoPlayer Auto-Pause/Resume**:
  The player currently handles `AudioBecomingNoisy` but does not listen for network state updates. If a user transitions from Wi-Fi to cellular or drops connection entirely, player streams will stall without a user-friendly message or automatic retry attempt once the connection is restored.

---

## 📋 Active Bug & Defect Registry

This table is reserved for logging active runtime errors, UI bugs, or customer-reported issues. 

| Bug ID | Description | Component | Severity | Status | Target Fix Version |
| :--- | :--- | :--- | :--- | :--- | :--- |
| BUG-001 | Clicking movie cards from HDHub4u extension opens empty details screen. | Scraper / VegaProviderRunner | High | Resolved | v1.0.1 |
| BUG-002 | "Decoder failed" playback error for 10-bit HEVC on devices lacking Main 10 hardware decoder. | Player / ExoPlayer | High | Resolved | v1.1.2 |

---

### BUG-002 Details: 10-bit HEVC Playback Decoder Failure
- **Symptoms**: Videos encoded as HEVC Main 10 (10-bit, e.g., `video/hevc, hvc1.2.4.L93.90, 10bit Luma, 10bit Chroma`) fail with `ERROR_CODE_DECODING_FAILED` (`format_supported=NO_EXCEEDS_CAPABILITIES`, `MediaCodecVideoDecoderException: Decoder failed: c2.goldfish.hevc.decoder` / Error 0xe) on Android Emulator or devices without a hardware Main 10 decoder.
- **Root Cause**: Device hardware decoder (e.g., emulator goldfish HEVC codec or older chipset) does not support HEVC Main 10 (10-bit profile). Default Media3 setup lacked software fallback decoders and failed abruptly without fallback or graceful user error recovery.
- **Fix**:
  1. Integrated `nextlib-media3ext` FFmpeg software video decoder fallback (`NextRenderersFactory` with `EXTENSION_RENDERER_MODE_ON` and `setEnableDecoderFallback(true)`).
  2. Configured `DefaultTrackSelector` with `setExceedRendererCapabilitiesIfNecessary(false)` to prevent selecting unplayable video tracks when alternative playable tracks exist.
  3. Added `isHevcMain10Supported()` capability detector helper in `:Player`.
  4. Extended `Player.Listener.onPlayerError` and `handlePlayerError` in `PlayerActivity` to catch `ERROR_CODE_DECODING_FAILED`, `ERROR_CODE_DECODER_INIT_FAILED`, and `ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES`, logging with tag `PlayerActivity` and presenting a D-pad focusable dialog offering to open the stream in an external player (VLC/MX Player) or close.

---

## 📝 Bug Reporting Guidelines

To add a new bug to this registry:
1. Assign a sequential **Bug ID** (e.g., `BUG-001`).
2. Provide a descriptive summary, including specific steps to reproduce and the target device context.
3. Classify **Severity** (`Low`, `Medium`, `High`, `Critical`).
4. Set **Status** to `New`, `In Progress`, or `Resolved`.
5. Link relevant source code files or log traces where applicable.
