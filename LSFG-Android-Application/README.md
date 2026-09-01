<div align="center">

# LSFG Android Application — A6xx Compatibility

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=20&pause=1000&center=true&vCenter=true&width=680&lines=Frame+Generation+on+Android;A6xx+Compatibility;MediaProjection+%2B+Vulkan;Tested+on+Adreno+619" />

<br>

![Android](https://img.shields.io/badge/Android-10%2B-brightgreen?logo=android)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-blue)
![Vulkan](https://img.shields.io/badge/API-Vulkan-red)
![A6xx](https://img.shields.io/badge/Compatibility-A6xx-purple)
![Kotlin](https://img.shields.io/badge/UI-Kotlin%20%2B%20Compose-orange)
![Native](https://img.shields.io/badge/Backend-C%2B%2B-blueviolet)

### Android frontend and native runtime for LSFG

Capture • Frame Generation • Overlay • Pacing • A6xx

</div>

---

## About

This is the Android application that drives the patched
[`lsfg-vk-android`](../lsfg-vk-android/) frame-generation backend.

The application:

- loads a user-supplied `Lossless.dll`
- extracts the required shaders on-device
- captures the target game through `MediaProjection`
- shares frames through `AHardwareBuffer`
- processes them through the LSFG Vulkan pipeline
- presents generated frames through an Android overlay

The visible frame path can be summarized as:

```text
Game
 ↓
MediaProjection
 ↓
VirtualDisplay
 ↓
ImageReader
 ↓
AHardwareBuffer
 ↓
Vulkan / LSFG
 ↓
Generated Frames
 ↓
System Overlay

```

No modification of the target game is required.

---

## A6xx Compatibility

This fork contains compatibility changes focused on Qualcomm
**A6xx GPUs**.

The current stable implementation has been physically tested on:

| Device | SoC | GPU | Status |
|---|---|---|---|
| Moto G34 | Snapdragon 695 | Adreno 619 | ✅ Working |

Confirmed on the tested device:

- LSFG initialization
- real frame generation
- generated-frame presentation
- 2x / 3x / 4x modes
- Performance Mode
- Low Latency Mode
- Flow Scale
- pacing controls
- Real / Generated / Total FPS monitoring

> [!NOTE]
> Other A6xx GPUs are still considered experimental until tested on
> physical hardware.

Compatibility is capability-based rather than relying only on the GPU name.

Different Android versions, OEM drivers, stock ROMs, custom ROMs and GSIs
may behave differently.

---

## What's included

### Capture & Overlay

- **MediaProjection capture**
- `VirtualDisplay` + `ImageReader`
- AHardwareBuffer-backed captured frames
- system overlay over the target application
- `SYSTEM_ALERT_WINDOW` overlay support
- optional `TYPE_ACCESSIBILITY_OVERLAY`
- Vulkan swapchain presentation path
- CPU fallback presentation path
- orientation-aware overlay handling
- immersive-mode handling

The capture path feeds AHardwareBuffers directly into the native render loop.

---

## Frame Generation

The application drives:

```text
LSFG_3_1
LSFG_3_1P
```

through the native `lsfg-vk-android` backend.

Available runtime controls include:

- 2x / 3x / 4x Frame Generation
- Flow Scale
- Performance Mode
- HDR Mode
- Anti-Artifacts
- Bypass
- Low Latency
- live LSFG parameter changes

Some parameter changes require the native LSFG context to be recreated,
while lighter settings can be applied without restarting the entire session.

---

## Frame Pacing

The application contains a configurable pacing system for controlling when
generated frames are presented.

Available controls include:

- VSync alignment
- VSync slack
- target FPS cap
- pacing presets
- Queue Depth
- EMA Alpha
- outlier rejection
- Low Latency mode

The HUD exposes live information such as:

```text
Real FPS
Generated FPS
Total FPS
Latency
Queue
Frame-time graph
```

This makes it possible to observe how different LSFG and pacing parameters
affect frame generation in real time.

---

## In-Game Overlay

The overlay provides access to LSFG controls without leaving the target game.

It supports:

- live settings drawer
- configurable drawer edge
- compact launcher
- automatic per-app overlay
- frame graph HUD
- runtime parameter changes
- real/generated/total FPS monitoring

The overlay is displayed independently from the target game's rendering
process.

---

## Touch Passthrough

Android overlay input behavior varies between Android versions and OEMs.

The application contains touch-passthrough infrastructure using:

```text
SYSTEM_ALERT_WINDOW
TYPE_ACCESSIBILITY_OVERLAY
TOUCHABLE_INSETS_REGION
```

The Accessibility path can be enabled on devices where the normal application
overlay encounters stricter touch filtering.

> [!NOTE]
> Touch behavior is still device-dependent and some upstream touch issues may
> remain.

---

## Capture Sources

### MediaProjection

MediaProjection is always used for the visible capture path.

The user must explicitly approve screen capture when starting a new session.

```text
Target App
 ↓
MediaProjection
 ↓
ImageReader
 ↓
AHardwareBuffer
 ↓
Native LSFG
```

### Shizuku Metrics

When enabled, Shizuku can provide additional privileged timing information
used for pacing diagnostics.

The visible frame stream still comes from MediaProjection.

Shizuku buffers are not used as the displayed LSFG video path.

---

## Architecture

### Session Flow

The main LSFG session follows approximately this sequence:

```text
Start Foreground Service
        ↓
Acquire MediaProjection
        ↓
Create Overlay
        ↓
Wait for Output Surface
        ↓
Start CaptureEngine
        ↓
Receive AHardwareBuffer
        ↓
Native Vulkan Session
        ↓
LSFG Frame Generation
        ↓
Present Generated Output
```

Waiting for the output surface before capture starts is important because
Android surface creation is asynchronous.

---

## JNI & Native Backend

The Kotlin application communicates with the native runtime through JNI.

Main bridge:

```text
session/NativeBridge.kt
        ↕ JNI
cpp/lsfg_jni.cpp
        ↓
cpp/lsfg_render_loop.cpp
```

The native render loop is responsible for:

- Vulkan initialization
- AHardwareBuffer import
- framegen context creation
- shader execution
- synchronization
- generated-frame output
- presentation

The Android application and LSFG backend may use separate Vulkan devices while
sharing AHardwareBuffers between them.

---

## AHardwareBuffer

Android cannot simply reuse every Linux external-memory path used by
`lsfg-vk`.

Instead, captured Android buffers are passed through:

```text
AHardwareBuffer*
```

and imported directly into Vulkan.

The backend uses Android Vulkan external-memory support to make these buffers
available to LSFG.

This allows the same captured image to move through:

```text
ImageReader
 ↓
AHardwareBuffer
 ↓
Host Vulkan
 ↓
LSFG Vulkan
 ↓
Generated Output
```

without needing to modify the target application.

---

## A6xx Vulkan Fallbacks

Older Qualcomm drivers may expose fewer Vulkan capabilities than newer
Adreno hardware.

The A6xx compatibility work therefore contains fallback paths where a safe
alternative exists.

The current compatibility work includes support for situations such as:

- Vulkan 1.1-class devices
- runtime Vulkan capability probing
- synchronization without timeline semaphores
- fence / binary semaphore fallback paths
- AHardwareBuffer-based image sharing
- descriptor fallback behavior
- older Qualcomm Vulkan implementations

The goal is to reject a device only when a genuinely required capability is
unavailable.

> [!IMPORTANT]
> A6xx compatibility does not mean every A6xx GPU or OEM driver is guaranteed
> to work.
>
> Physical device testing is still required.

---

## Context Reinitialization

Some LSFG parameters require rebuilding the native context.

Conceptually:

```text
Setting Changed
      ↓
Request Re-init
      ↓
Destroy Context
      ↓
Initialize Context
      ↓
Resume Frame Generation
```

The application serializes these operations so multiple rapid setting changes
do not create overlapping native context rebuilds.

Settings that do not require Vulkan allocations can use lighter hot-apply
paths instead.

---

## Shader Pipeline

The application does **not** distribute Lossless Scaling shaders.

When the user selects their legitimate `Lossless.dll`:

```text
Lossless.dll
 ↓
ShaderExtractor
 ↓
Native PE Parser
 ↓
DXBC Resources
 ↓
SPIR-V
 ↓
Vulkan Shader Validation
```

The extracted shaders are stored in the application's private storage.

The temporary DLL copy is removed after extraction.

> [!IMPORTANT]
> Never commit or redistribute:
>
> - `Lossless.dll`
> - extracted proprietary shaders
> - proprietary Lossless Scaling assets
>
> Users must provide their own legitimate copy.

---

## Project Structure

```text
LSFG-Android-Application/
│
├── app/
│   ├── src/main/
│   │
│   ├── java/com/lsfg/android/
│   │   ├── ui/
│   │   ├── session/
│   │   └── prefs/
│   │
│   ├── cpp/
│   │   ├── lsfg_jni.cpp
│   │   ├── lsfg_render_loop.cpp
│   │   ├── android_vk_session.cpp
│   │   ├── android_vk_probe.cpp
│   │   ├── android_shader_loader.cpp
│   │   ├── ahb_image_bridge.cpp
│   │   ├── nnapi_npu.cpp
│   │   ├── nnapi_postprocess.cpp
│   │   ├── gpu_postprocess.cpp
│   │   ├── cpu_postprocess.cpp
│   │   ├── crash_reporter.cpp
│   │   └── CMakeLists.txt
│   │
│   ├── res/
│   └── AndroidManifest.xml
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew
```

---

## Main Components

### `ui/`

Jetpack Compose interface.

Contains:

- main application UI
- navigation
- configuration screens
- application picker
- DLL picker
- tutorial
- projection request flow
- legal screen

### `session/`

Runtime Android services.

Contains:

- foreground service
- MediaProjection handling
- CaptureEngine
- overlay manager
- Accessibility integration
- settings drawer
- crash reporting
- JNI bridge

### `cpp/`

Native runtime.

Contains:

- JNI
- Vulkan
- AHardwareBuffer handling
- LSFG render loop
- shader extraction
- post-processing scaffolding
- crash handling

---

## Build

From the application directory:

```sh
cd LSFG-Android-Application
./gradlew :app:assembleDebug
```

Build Release:

```sh
./gradlew :app:assembleRelease
```

The project uses:

```text
Kotlin
Jetpack Compose
C++
JNI
Vulkan
AHardwareBuffer
CMake
Android NDK
Gradle
```

The native frame-generation backend is compiled together with the Android
application.

---

## Release Builds

Release builds may use:

- R8
- code minification
- resource shrinking
- ARM64-only packaging
- release signing

These optimizations reduce APK size without changing the intended LSFG
frame-generation pipeline.

Signing credentials and keystores should never be committed to the repository.

---

## Device Requirements

Minimum requirements include:

- Android 10+
- ARM64 device
- Vulkan support
- Android AHardwareBuffer Vulkan support
- screen capture support
- overlay support

The application checks runtime Vulkan capabilities before LSFG initialization.

The A6xx compatibility path provides fallbacks for several capabilities that
may not exist on older Qualcomm Vulkan drivers.

---

## Permissions

| Permission / API | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Display LSFG output over the target game |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Run screen capture |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Support additional foreground runtime functionality |
| `POST_NOTIFICATIONS` | Foreground-service notification |
| `BIND_ACCESSIBILITY_SERVICE` | Optional Accessibility overlay / touch path |
| Shizuku permission | Optional privileged timing diagnostics |
| MediaProjection consent | Capture the screen during an LSFG session |

---

## Compatibility Testing

When reporting compatibility results, include:

```text
Device:
SoC:
GPU:
Android version:
Stock ROM / Custom ROM / GSI:
Vulkan API:
Vulkan driver:
LSFG multiplier:
Performance Mode:
Low Latency:
Real FPS:
Generated FPS:
Total FPS:
Result:
```

Physical testing is especially useful for expanding A6xx support.

---

## Known Limitations

LSFG Android uses Android screen capture instead of directly hooking into
the target application's Vulkan swapchain.

Because of this:

- additional latency compared with native desktop LSFG is expected
- MediaProjection permission is required
- overlay behavior varies between Android implementations
- touch passthrough can vary between OEMs
- Vulkan drivers behave differently across devices
- GSIs may behave differently from stock ROMs
- some upstream LSFG-Android issues may still remain
- compatibility with untested A6xx GPUs is not guaranteed

---

## Work in Progress

Some native infrastructure already exists for future image-processing features.

These areas are still experimental or incomplete:

- NNAPI / NPU post-processing
- GPU post-processing
- upscaling
- CPU image enhancement
- LUT processing
- vibrance / saturation adjustments
- zero-copy output improvements

These features should not be considered stable until fully integrated and
tested.

---

## Security & Distribution

The application uses several Android APIs that can trigger warnings from
Android or security scanners, including:

- MediaProjection
- system overlays
- AccessibilityService
- native JNI libraries
- Vulkan
- optional Shizuku integration

These APIs are used for the application's capture, overlay and frame-generation
functionality.

The application does not require `Lossless.dll` to be bundled into the APK.

---

## Credits

This project would not exist without:

### FrankBarretta

[`FrankBarretta/LSFG-Android`](https://github.com/FrankBarretta/LSFG-Android)

Creator of the original Android port, including its MediaProjection capture
pipeline, overlay system, Kotlin/Compose application, JNI integration and
Android frame-generation architecture.

### PancakeTAS & lsfg-vk Contributors

[`PancakeTAS/lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk)

Creators and contributors of the original Linux Vulkan LSFG implementation
that forms the foundation of the native backend.

### THS / Lossless Scaling

Creators of **Lossless Scaling** and its frame-generation technology.

Lossless Scaling assets are not distributed by this project.

---

## License

The LSFG Android Application is distributed under the
**GNU General Public License v3.0 (GPL-3.0)**.

See [`LICENSE`](LICENSE) for the complete license terms.

The `lsfg-vk-android` component is licensed separately under the MIT License.

Always preserve the original license files, copyright notices and attribution
when redistributing this project.

`Lossless.dll` and proprietary Lossless Scaling assets are not distributed
by this project.

---

<div align="center">

## LSFG Android Application — A6xx Compatibility

**Kotlin • Vulkan • AHardwareBuffer • Frame Generation • A6xx**

Built on top of LSFG-Android and lsfg-vk.

</div>
