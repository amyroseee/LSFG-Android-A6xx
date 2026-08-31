# LSFG-Android-Application

The Android app that drives the patched [`lsfg-vk-android`](../lsfg-vk-android/)
framegen library. It picks up a user-supplied `Lossless.dll`, extracts the
SPIR-V shaders on-device, captures the screen via `MediaProjection`, runs
Lossless Scaling frame generation on the captured stream, and composites the
generated frames into a system overlay sitting on top of the target game.

> [!NOTE]
> Sideload-only. End-to-end frame generation works on Adreno 7xx-class GPUs
> and newer (Snapdragon 8 Gen 2 / Gen 3 / Gen 4 verified). Older Adreno and
> most Mali devices are missing `VK_EXT_robustness2` and fall back to
> capture-only mirror mode (the overlay still shows the game, no
> interpolation).

## What's in the box

### Capture and overlay

- **MediaProjection capture** through `VirtualDisplay` + `ImageReader`,
  delivering AHardwareBuffer-backed frames straight into the native render
  loop.
- **Overlay host**: `SYSTEM_ALERT_WINDOW` by default, or
  `TYPE_ACCESSIBILITY_OVERLAY` when the user enables `LsfgAccessibilityService`.
  The accessibility-overlay path is the recommended opt-in for OEMs with
  strict untrusted-touch filters or aggressive background killers.
- **TextureView output** with a Vulkan swapchain composition path on top of
  the CPU-blit fallback.
- **Touch passthrough at full opacity** via an empty `TOUCHABLE_INSETS_REGION`
  rather than `FLAG_NOT_TOUCHABLE` (which AOSP would clamp to 0.8 alpha).
- **Rotation- and immersive-mode-aware** overlay placement and re-layout.

### Frame generation

- **LSFG_3_1 / LSFG_3_1P** running on framegen's internal Vulkan device,
  fed AHardwareBuffers shared from the app's session.
- **Multiplier** 2× to 8×.
- **Flow scale** 0.25 to 1.0.
- **Performance mode**, **HDR mode**, **anti-artifacts**, **bypass**.
- **Live re-init** on parameter change, serialized so concurrent slider
  releases don't drop the session.

### Pacing

- **Vsync alignment** with configurable slack and per-slot budget.
- **Pacing presets** for common targets, plus a manual target FPS cap.
- **Queue depth**, **EMA alpha** for jitter smoothing, outlier rejection.
- **Frame graph HUD** with frame-time graph and a `real X fps / total Y fps`
  counter (`total = capture × multiplier` when active, `total = capture` when
  bypassed or framegen failed to initialize).

### UX

- **First-launch tutorial** — multi-step walkthrough with screenshots covering
  Accessibility setup (LSFG Touch Passthrough service, the Restricted-Settings
  unblock that sideloaded apps need on Android 13+) and the in-game overlay
  menu. Gated on a one-time preference so it doesn't replay every launch.
- **Settings drawer** in-game, with two entry modes (icon-button or edge-swipe)
  and four selectable edges. The collapsed strip is narrow and lets touches
  pass through to the game; swipe inward to expand.
- **Automatic per-app overlay** — pick target apps and the overlay arms when
  one of them comes to the foreground.
- **Draggable launcher dot** with edge snapping.
- **Crash reporter** capturing both Java/Kotlin uncaught exceptions and native
  signals (SIGSEGV, SIGABRT, ...) with stack-walking. Surfaces a one-shot
  dialog on the next launch with a one-tap share intent for bug reports.

### Capture sources

- **MediaProjection** — always used for the visible frames. Consent prompt
  on every session start. This is the default source.
- **Shizuku metrics mode** — still uses MediaProjection for the visible video
  path. In parallel, Shizuku binds a user service for the selected target UID
  and provides a privileged timing-only side channel that's fed to native via
  `reportShizukuTiming` for pacing decisions. Shizuku buffers are never made
  visible, so a broken or black privileged buffer cannot blackout the
  overlay.

## Architecture

### Session flow

The critical sequence in `LsfgForegroundService.handleStart`:

1. Start the foreground service with type `mediaProjection|specialUse` and
   post the persistent notification **first** (FGS rules).
2. Acquire the `MediaProjection` token from the consent intent that
   `MainActivity` collected.
3. `OverlayManager.show()` adds the overlay view via `WindowManager`.
4. **Wait for `onSurfaceReady` before starting capture.** Surface creation is
   async; launching the target app before the surface is valid leaves the
   overlay empty on PowerVR and some OEMs.
5. Start `CaptureEngine` (MediaProjection → VirtualDisplay → ImageReader →
   AHardwareBuffer) and, when enabled, `ShizukuCaptureEngine` for the
   privileged timing side channel. Then launch the target app via
   `Intent.ACTION_MAIN` and re-assert z-order.

Surface lifecycle is event-driven: `onSurfaceReady(surface, w, h)` can fire
multiple times (orientation / immersive changes), and the service coalesces
duplicate events using `lastSurface` / `lastSurfaceW` / `lastSurfaceH`
identity checks to avoid retargeting the VirtualDisplay onto an identical
Surface (which freezes the overlay on PowerVR).

### JNI ↔ native

All JNI lives in `session/NativeBridge.kt` ↔ `cpp/lsfg_jni.cpp`. The native
entry points are a thin shim over `cpp/lsfg_render_loop.cpp`, which owns a
worker thread and:

- holds a persistent `VulkanSession` (our own device, managed through a
  per-session `VolkDeviceTable` so framegen's internal `volkLoadDevice`
  doesn't clobber our function pointers);
- imports each `ImageReader` AHardwareBuffer into our session, performs a
  `FOREIGN_EXT` queue-family ownership transfer, and hands it to framegen via
  `LSFG_3_1::createContextFromAHB` so both devices read the same AHB
  (Adreno/Mali drivers refuse `vkGetMemoryFdKHR` on AHB-imported memory,
  which is why the FD path from upstream is unusable here);
- runs `presentContext`, syncs across devices via `LSFG_3_1::waitIdle()`
  (Vulkan defines no cross-device semaphore), and delivers each output AHB
  to the overlay's `ANativeWindow`.

### Re-init coordination

Live parameter changes from the settings drawer require a full native context
rebuild (`destroyContext` → `initContext`). `LsfgForegroundService` serializes
these through `reinitInFlight` / `reinitRequested` flags:

- Only one re-init runs at a time.
- A change during an in-flight re-init sets `reinitRequested` and the worker
  re-runs once done, picking up the freshest preferences. Without this,
  mid-re-init changes were silently dropped.
- `shuttingDown` is set in `onDestroy` and future re-init requests are
  dropped; `onDestroy` waits up to 1.5 s for an in-flight re-init before
  calling `shutdownRenderLoop()` to avoid racing `destroyContext` against a
  worker that is still starting up.

Anything that does **not** need a Vulkan rebuild (bypass, anti-artifacts,
vsync period, pacing tunables, Shizuku timing) has a dedicated hot-apply JNI
entry — preferred whenever possible. Only parameters that change Vulkan
allocations bump the full re-init path.

### Overlay and touch passthrough

`OverlayManager` does **not** set `FLAG_NOT_TOUCHABLE` because AOSP clamps
`TYPE_APPLICATION_OVERLAY` to 0.8 alpha when that flag is set. Instead an
empty `TOUCHABLE_INSETS_REGION` tells `InputDispatcher` no pixel is touchable,
which passes events through at full opacity. When the user enables
`LsfgAccessibilityService`, the overlay is hosted from the accessibility
context as `TYPE_ACCESSIBILITY_OVERLAY`, which is more robust against OEM
background-killers and strict-touch filters. The accessibility service's
gesture-forwarding implementation is stubbed — only the passthrough and
z-order wiring is in place.

### Shader pipeline

On first DLL selection: `ShaderExtractor` → native `extractShaders` →
`android_shader_loader.cpp` parses the PE via `pe-parse`, translates each
DXBC resource via DXVK's `dxbc` library, and writes one `.spv` per resource
ID into `filesDir/spirv/`. Then `probeShaders` runs a headless Vulkan 1.1
device and calls `vkCreateShaderModule` on every blob to catch driver
rejection before the full pipeline is attempted. The `Lossless.dll` copy is
deleted after extraction.

> [!IMPORTANT]
> Do not commit the DLL or extracted SPIR-V. This app is sideload-only and
> the DLL is not redistributable.

## Project layout

```
LSFG-Android-Application/                       # Android Studio project root
  app/
    src/main/
      java/com/lsfg/android/
        ui/                         # Compose screens + navigation
                                    #   MainActivity, LsfgNavHost, HomeScreen,
                                    #   ParamsScreen, DllPickerScreen,
                                    #   AppPickerScreen, AutomaticOverlayScreen,
                                    #   TutorialScreen, ProjectionRequestActivity,
                                    #   LegalScreen, theme/, components
        session/                    # Foreground service, overlay, capture,
                                    #   accessibility, settings drawer,
                                    #   crash reporter, JNI bridge
        prefs/                      # SharedPreferences-backed config + enums
                                    #   (CaptureSource, OverlayMode, DrawerEdge,
                                    #   NpuPostProcessingPreset, etc.)
        LsfgApplication.kt
        FeatureFlags.kt, AppIconLoader.kt
      aidl/com/lsfg/android/shizuku/   # Shizuku user-service AIDL
      cpp/                          # JNI + CMake — pulls in
                                    #   ../../../../../lsfg-vk-android/framegen/
        lsfg_jni.cpp                # JNI entry points
        lsfg_render_loop.cpp        # Worker thread, framegen wiring, presentation
        android_vk_session.cpp      # Persistent Vulkan device + VolkDeviceTable
        android_vk_probe.cpp        # Headless Vulkan smoke test for shaders
        android_shader_loader.cpp   # DXBC -> SPIR-V extraction + name->id map
        ahb_image_bridge.cpp        # AHardwareBuffer <-> VkImage import
        nnapi_npu.cpp               # NNAPI runtime wrapper
        nnapi_postprocess.cpp       # NPU post-processing stage
        gpu_postprocess.cpp         # GPU post-processing stage
        cpu_postprocess.cpp         # CPU enhancement stage
        crash_reporter.cpp          # Native signal handlers + ring log
        unicode_minimal.cpp         # String utilities
        CMakeLists.txt
      res/                          # strings, themes, drawables (tutorial
                                    #   step images, launcher icon),
                                    #   accessibility + data-extraction XML
      AndroidManifest.xml
    build.gradle.kts
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
```

## Build

```sh
cd LSFG-Android-Application        # this directory
./gradlew :app:assembleDebug         # or :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/debug/`. Install with `adb install`.

Toolchain: Android Studio Ladybug+, NDK 27.0.12077973, CMake 3.22.1, JDK 17,
C++20, Kotlin Compose plugin. ABIs: `arm64-v8a` (production) and `x86_64`
(emulator only). `minSdk=29` (Android 10), `targetSdk=35` (Android 15).

Release signing is driven by `gradle.properties` (`LSFGAndroid_STORE_FILE`,
`LSFGAndroid_STORE_PASSWORD`, `LSFGAndroid_KEY_ALIAS`,
`LSFGAndroid_KEY_PASSWORD`). If those properties are absent the release
build falls back to the debug signing config.

## Required device features

- **Vulkan 1.2+**.
- `VK_ANDROID_external_memory_android_hardware_buffer`.
- `VK_KHR_external_memory` + `VK_KHR_sampler_ycbcr_conversion`.
- **`VK_EXT_robustness2`** — required by framegen for `nullDescriptor`.
  Available on recent Adreno (≥ 7xx-class). If absent, the session creates
  fine but `LSFG_3_1::initialize` throws and the app falls back to
  capture-only mirror mode.
- `VK_EXT_queue_family_foreign` — recommended; used for AHB ownership
  transitions. Falls back to `VK_QUEUE_FAMILY_EXTERNAL` when absent.

The app checks for these at startup and surfaces an actionable error if the
device doesn't support the mandatory ones.

## Permissions

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Host the overlay over the target game. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Run the visible capture path. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Run the Shizuku-only timing path without holding a MediaProjection token (subtype: `lsfg_shizuku_capture_pacing`). |
| `POST_NOTIFICATIONS` | Persistent foreground-service notification. |
| `BIND_ACCESSIBILITY_SERVICE` (optional) | Host the overlay as `TYPE_ACCESSIBILITY_OVERLAY` — more robust on strict OEMs. |
| `moe.shizuku.manager.permission.API_V23` (optional) | Shizuku metrics mode. |
| MediaProjection consent | Granted by the user on every session start. |

## Limitations

- **No swapchain hooking on non-rooted Android.** Android 12+ blocks loading
  external code into non-debuggable processes, so this app cannot inject into
  a target game's Vulkan swapchain. It runs frame generation on a
  `MediaProjection` capture instead.
- **MediaProjection adds latency** (~50–80 ms typical, plus the cost of the
  CPU blit when the swapchain output path is unavailable).
- **Google Play policy.** `SYSTEM_ALERT_WINDOW` + screen capture +
  `AccessibilityService` together violate Play policy. This app is
  distributable only as a sideloaded APK.
- **User-supplied `Lossless.dll`.** The user must own a legitimate Steam copy
  and provide the DLL via the Storage Access Framework on first launch.
- For the full-quality on-GPU experience as on Linux, a Magisk module that
  installs a Vulkan implicit layer into `/system/etc/vulkan/implicit_layer.d/`
  would be the only realistic path. It is not included here.

## TO-DO

Work in progress, not yet complete. The native scaffolding is in the tree
(`cpu_postprocess.cpp`, `gpu_postprocess.cpp`, `nnapi_npu.cpp`,
`nnapi_postprocess.cpp`) but the corresponding settings UI is currently
hidden because the pipelines still need to be wired up and tuned end-to-end:

- **NPU post-processing** via NNAPI (planned presets: sharpen, detail boost,
  chroma clean, game crisp). Requires NNAPI hardware acceleration on the
  device.
- **GPU post-processing** stage (upscaling / image-blit pass on top of the
  framegen output).
- **CPU post-processing** kernels (LUT, vibrance, saturation, vignette).
- **Zero-copy output blit**: the swapchain output path covers most cases,
  but the CPU-blit fallback could be replaced with a direct AHB-to-AHB
  import for energy efficiency.

## License

This app — that is, everything inside the `LSFG-Android-Application/` directory — is
released under a **Custom License: No Play Store, No Commercial Use**. See
[`LICENSE`](LICENSE) in this directory for the full terms. In short:

- **Personal, non-commercial use** is permitted.
- **Modification and redistribution** in source or binary form are permitted
  for non-commercial purposes, provided the license text is reproduced and
  attribution is preserved.
- **Publishing on Google Play, the App Store, or any other commercial /
  curated mobile application marketplace is NOT permitted.** The app is
  sideload-only by design and by license.
- **Commercial use is NOT permitted** — no paid distribution, no paid
  bundling, no paid services, no monetization (ads, paywalls, sponsorships,
  donation walls inside the app).
- The app does not bundle `Lossless.dll` or any extracted Lossless Scaling
  shaders, and redistributing it together with such proprietary assets is
  not permitted under this license.

The patched [`lsfg-vk-android`](../lsfg-vk-android/) submodule one directory
above is licensed separately under the **MIT License** (inherited from
upstream `lsfg-vk`). See [`../lsfg-vk-android/LICENSE.md`](../lsfg-vk-android/LICENSE.md).
The repository root is also MIT, scoped to its top-level files only — see
[`../LICENSE`](../LICENSE) and [`../README.md`](../README.md).

`Lossless.dll` is **not** part of this project. It is the property of its
copyright holder (THS / Lossless Scaling). This project does not distribute,
redistribute, or bundle any part of `Lossless.dll`. Users must provide their
own copy from a legitimately purchased Steam license.
