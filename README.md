# LSFG-Android — frame generation on Android via the lsfg-vk pipeline

[![Discord](https://img.shields.io/discord/1496212333595463780?label=Discord&logo=discord)](https://discord.gg/CkuumNJ7s4)

LSFG-Android brings the [`lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk)
Vulkan frame-generation pipeline to Android. Because Android 12+ blocks
loading external code into non-debuggable processes, the layer can't hook
another app's swapchain the way the Linux implicit layer does. Instead, the
app runs frame interpolation on a `MediaProjection` capture and composites
the generated frames in a system overlay sitting on top of the target game.
End-to-end frame generation works today on Adreno 7xx-class GPUs and newer.


## Video

[LSFG-Android 0.1.0 Test](https://youtu.be/Lx-_b9AJdK0)



## Repository layout

| Path | What it is |
|---|---|
| [`LSFG-Android/`](LSFG-Android/) | Android Studio project — Kotlin + Jetpack Compose UI, JNI/C++ render loop. The user-facing app. |
| [`lsfg-vk-android/`](lsfg-vk-android/) | Submodule. **Branch of [`lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk) 1.0.0** with Android-specific patches added on top (AHardwareBuffer-based image sharing, `createContextFromAHB`, `waitIdle`). All patches are guarded by `#ifdef __ANDROID__`, so the original Linux build path still works unchanged. |

The Android app pulls `framegen/` directly from the submodule via CMake
`add_subdirectory()`. There is no separate prebuilt `.so` to ship — building
the app builds the framegen library transparently for `arm64-v8a` and
`x86_64`.

## What it does

- **Frame generation (LSFG_3_1 / LSFG_3_1P)** running on-GPU via AHardwareBuffer
  sharing between the app's Vulkan session and framegen's internal device.
- **Live in-game settings drawer**: multiplier (2×–8×), flow scale (0.25–1.0),
  performance / HDR mode, anti-artifacts, bypass, vsync alignment with slack
  control, pacing presets, target FPS cap, queue depth, EMA jitter smoothing.
  Most parameters re-init the native context on the fly; bypass / pacing /
  Shizuku timing have hot-apply paths that don't drop the session.
- **Automatic per-app overlay** — pick target apps and the overlay arms when
  one of them comes to the foreground. Two entry modes: a draggable launcher
  dot or an icon button on the configurable edge of the screen.
- **First-launch tutorial** that walks through the Accessibility setup
  (the touch-passthrough service and the Restricted-Settings unblock that
  sideloaded apps trigger on Android 13+).
- **Touch passthrough** at full opacity. The overlay can be hosted as a
  `SYSTEM_ALERT_WINDOW` or, when the user enables `LsfgAccessibilityService`,
  as a `TYPE_ACCESSIBILITY_OVERLAY` — the latter is the opt-in path for OEMs
  with strict untrusted-touch filters.
- **Capture sources**: MediaProjection (default, used for the visible frames
  on every session) and Shizuku metrics mode, which adds a privileged
  target-UID-filtered timing side channel for pacing diagnostics without
  ever feeding Shizuku buffers into the visible video path.
- **Post-processing pipelines**: NPU presets via NNAPI (sharpen, detail boost,
  chroma clean, game crisp), GPU upscaling stage, and CPU enhancement
  (LUT, vibrance, saturation, vignette).
- **Frame graph HUD** with real-vs-total FPS counter, frame-time graph, and
  pacing diagnostics.
- **Crash reporter** capturing both Java/Kotlin uncaught exceptions and
  native signals (SIGSEGV, SIGABRT, …) with stack-walking; one-tap share via
  `ACTION_SEND` for bug reports.
- **Vulkan swapchain output path** for efficient frame presentation on top of
  the CPU-blit fallback.
- **Rotation and immersive-mode aware** overlay components.

> [!IMPORTANT]
> You need a legitimately purchased copy of Lossless Scaling. The `Lossless.dll`
> is **not** shipped, downloaded, or bundled by anything in this repository.
> The user picks their own DLL via the Storage Access Framework, the app
> extracts the shaders on-device into its private storage, then deletes the
> DLL copy. Nothing about this project distributes Lossless Scaling assets.

## Build

```sh
cd LSFG-Android
./gradlew :app:assembleDebug         # or :app:assembleRelease
```

The APK lands in `LSFG-Android/app/build/outputs/apk/debug/app-debug.apk`.
Install with `adb install`.

Toolchain: Android Studio Ladybug+, NDK 27.0.12077973, CMake 3.22.1, JDK 17,
C++20. ABIs: `arm64-v8a` (production) and `x86_64` (emulator only).
`minSdk=29` (Android 10), `targetSdk=35` (Android 15).

CMake automatically resolves the submodule via the relative path
`../../../../../lsfg-vk-android` from the JNI sources. Keep both folders
side-by-side as in this repository's layout — moving or renaming either one
breaks the native build.

For the standalone Linux build of the patched `lsfg-vk`, see
[`lsfg-vk-android/README.md`](lsfg-vk-android/README.md). The Android-specific
patches are no-ops on non-Android targets, so the upstream build commands
work unchanged.

## Platform limits (read once)

On non-rooted Android there is **no equivalent to Linux's Vulkan implicit
layer mechanism**. Android 12+ explicitly blocks loading external code into
non-debuggable processes, so this app cannot hook another app's Vulkan
swapchain. Frame generation runs on a `MediaProjection` screen-capture stream
instead, and the result is composited in a system overlay over the target.

That adds roughly 50–80 ms of latency versus the Linux Vulkan layer. It is a
platform constraint, not a bug. `MediaProjection` also requires explicit user
consent on every session start and surfaces a persistent system indicator.
The combination of `SYSTEM_ALERT_WINDOW` + screen capture + `AccessibilityService`
violates Google Play policy, so this app is distributable only as a sideloaded
APK. A Magisk module installing a Vulkan implicit layer into
`/system/etc/vulkan/implicit_layer.d/` would be the only realistic path to
match the Linux experience, and is out of scope here.

## Component-level READMEs

- [`LSFG-Android/README.md`](LSFG-Android/README.md) — app architecture,
  feature breakdown, device requirements, native module layout.
- [`lsfg-vk-android/README.md`](lsfg-vk-android/README.md) — the framegen
  library, the Android patch set, and the precise diff against upstream
  `lsfg-vk` 1.0.0.

## Credits

This project would not exist without the work of:

- **[PancakeTAS](https://github.com/PancakeTAS) and the lsfg-vk contributors** —
  authors of the original [`lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk)
  Vulkan frame-generation layer, which is the entire backbone of this port.
- **THS / Lossless Scaling** — original authors of the Lossless Scaling
  frame-generation shaders. The shaders are extracted on-device from the
  user's own legitimately purchased copy of `Lossless.dll` and are never
  redistributed by this project.
- **[FrankBarretta](https://github.com/FrankBarretta)** — Android port
  (this repository): JNI/Vulkan glue, AHardwareBuffer-based image sharing,
  MediaProjection capture pipeline, overlay/foreground service, accessibility-
  overlay touch passthrough, Compose UI, settings drawer, automatic overlay,
  draggable launcher dot, first-run tutorial, Shizuku integration, frame graph HUD, 
  crash reporter, Vulkan swapchain output, and the upstream patches added 
  under `#ifdef __ANDROID__` in the [`lsfg-vk-android`](lsfg-vk-android/) submodule.

Third-party libraries used by the native build: [`volk`](https://github.com/zeux/volk),
[`pe-parse`](https://github.com/trailofbits/pe-parse), DXVK's `dxbc` translator,
and [Shizuku](https://github.com/RikkaApps/Shizuku) for the privileged timing
side channel.

If you fork this project or build something on top of it, please keep both
the upstream `lsfg-vk` attribution and the LSFG-Android port attribution
intact (this is also a requirement of the licenses below).

## License

The top-level files of this repository are released under the **MIT License** —
see [`LICENSE`](LICENSE). The two main subdirectories carry their own
licenses, which prevail over the root MIT license within their respective
trees:

| Subdirectory | License | File |
|---|---|---|
| [`LSFG-Android/`](LSFG-Android/) | **Custom License — No Play Store, No Commercial Use** | [`LSFG-Android/LICENSE`](LSFG-Android/LICENSE) |
| [`lsfg-vk-android/`](lsfg-vk-android/) | **MIT** (inherited from upstream `lsfg-vk`) | [`lsfg-vk-android/LICENSE.md`](lsfg-vk-android/LICENSE.md) |

If you redistribute the repository as a whole, reproduce all three license
files and respect the most restrictive terms applicable to each subtree —
in particular, the LSFG-Android app may not be published on Google Play or
any other commercial app store, and may not be used commercially.

`Lossless.dll` is the property of THS / Lossless Scaling and is **not**
distributed by this project under any circumstances.
