<div align="center">

# LSFG Android — A6xx Compatibility

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=20&pause=1000&center=true&vCenter=true&width=650&lines=Frame+Generation+on+Android;A6xx+Compatibility;2x+%E2%80%A2+3x+%E2%80%A2+4x+Frame+Generation;Tested+on+Adreno+619" />

<br>

![Android](https://img.shields.io/badge/Android-10%2B-brightgreen?logo=android)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-blue)
![Vulkan](https://img.shields.io/badge/API-Vulkan-red)
![A6xx](https://img.shields.io/badge/Compatibility-A6xx-purple)
![Tested](https://img.shields.io/badge/Tested-Adreno%20619-blueviolet)

[![Latest Release](https://img.shields.io/github/v/release/SEU_USUARIO/SEU_REPO?label=Latest%20Release)](../../releases/latest)
[![Downloads](https://img.shields.io/github/downloads/SEU_USUARIO/SEU_REPO/total?label=Downloads)](../../releases)

### Lossless Scaling Frame Generation on Android

Experimental compatibility improvements for **Qualcomm A6xx GPUs**.

</div>

---

## About

**LSFG Android — A6xx Compatibility** is a modified fork of
[FrankBarretta/LSFG-Android](https://github.com/FrankBarretta/LSFG-Android),
focused on expanding LSFG frame generation compatibility to Qualcomm
**A6xx GPUs**.

LSFG-Android brings the
[`lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk)
frame-generation pipeline to Android.

Instead of directly hooking into another application's Vulkan swapchain,
frame interpolation runs from an Android `MediaProjection` capture and the
generated frames are displayed through a system overlay.

### Current status

✅ Frame generation working on **Adreno 619**

✅ A6xx compatibility improvements

✅ 2x / 3x / 4x Frame Generation

✅ Performance Mode

✅ Low Latency Mode

✅ Flow Scale control

✅ Frame pacing controls

✅ Real / Generated / Total FPS HUD

> [!NOTE]
> Compatibility with other A6xx GPUs is currently experimental and
> requires physical device testing.

---

## Tested Devices

| Device | SoC | GPU | Status |
|---|---|---|---|
| Moto G34 | Snapdragon 695 | Adreno 619 | ✅ Working |

More device reports are welcome.

If you test the project on another A6xx GPU, feel free to open an Issue
with your results.

---

## How it works

Android does not provide the same Vulkan implicit-layer mechanism used by
LSFG on Linux for hooking directly into another application's swapchain.

Instead, LSFG-Android uses a capture and overlay pipeline:

```text
Game
 ↓
MediaProjection
 ↓
AHardwareBuffer
 ↓
Vulkan / LSFG
 ↓
Generated Frames
 ↓
Android Overlay
```

This allows frame generation to work without modifying the target game.

---

## Features

- **2x / 3x / 4x Frame Generation**
- LSFG frame interpolation
- AHardwareBuffer-based Vulkan processing
- A6xx compatibility improvements
- Performance Mode
- Low Latency Mode
- Flow Scale control
- Anti-artifact controls
- Frame pacing configuration
- Target FPS controls
- VSync alignment
- Queue depth control
- EMA jitter smoothing
- Real / Generated / Total FPS monitoring
- Frame-time graph
- In-game settings overlay
- Automatic per-app overlay
- ARM64 Android support

---

## A6xx Compatibility

The main goal of this fork is improving compatibility with Qualcomm
**A6xx GPUs**.

The current implementation has been physically tested and confirmed working
on:

```text
Snapdragon 695
Adreno 619
```

Frame generation, generated-frame presentation and the main LSFG options
work correctly on the tested device.

Other A6xx GPUs such as:

```text
Adreno 610
Adreno 612
Adreno 616
Adreno 618
Adreno 620
Adreno 630
Adreno 640
Adreno 650
```

may also work, but compatibility is **not guaranteed yet**.

Different Android versions, OEM Vulkan drivers and device implementations
can affect compatibility.

---

## Device Testing

If you test another device, please include:

```text
Device:
SoC:
GPU:
Android version:
Vulkan driver:
LSFG multiplier:
Performance Mode:
Result:
```

Reports from other A6xx devices will help improve the compatibility matrix.

---

## Requirements

- Android 10+
- ARM64 device
- Vulkan support
- Compatible GPU
- Screen capture permission
- Overlay permission
- Legitimately purchased copy of Lossless Scaling

> [!IMPORTANT]
> You need a legitimately purchased copy of **Lossless Scaling**.
>
> `Lossless.dll` is **not shipped, downloaded or distributed** by this
> repository.
>
> The user must provide their own legitimate copy.

---

## Download

<div align="center">

### [Download Latest Release](../../releases/latest)

Stable releases are tested before publication.

Pre-releases contain experimental changes and may contain bugs.

</div>

---

## Releases

### Stable

Stable releases contain changes that have already been physically tested.

### Pre-release

Pre-releases may contain experimental work such as:

- A6xx compatibility improvements
- additional GPU support
- UI changes
- overlay improvements
- touch fixes
- driver experiments
- performance improvements

Use pre-releases for testing and report any regressions through GitHub Issues.

---

## Build

Clone the repository:

```sh
git clone https://github.com/SEU_USUARIO/SEU_REPO.git
cd SEU_REPO/LSFG-Android-Application
```

Build a debug APK:

```sh
./gradlew :app:assembleDebug
```

Or build a release APK:

```sh
./gradlew :app:assembleRelease
```

The project uses:

- Kotlin
- Jetpack Compose
- C++
- JNI
- Vulkan
- AHardwareBuffer
- CMake
- Android NDK

The native LSFG components are built together with the Android application.

---

## Repository Structure

| Path | Description |
|---|---|
| `LSFG-Android-Application/` | Android application, Kotlin UI and JNI/C++ render pipeline |
| `lsfg-vk-android/` | Android-compatible `lsfg-vk` frame-generation backend |

The Android application links the native frame-generation components through
CMake.

Keep the repository structure intact when building from source.

---

## Known Limitations

LSFG-Android uses Android screen capture and overlay APIs rather than directly
hooking into the target application's Vulkan swapchain.

Because of this:

- additional latency compared with desktop/Linux LSFG is expected
- compatibility may vary between Android ROMs
- some GSIs may behave differently from stock ROMs
- touch passthrough behavior may vary between devices
- Vulkan driver behavior varies between manufacturers
- A6xx compatibility may vary between devices
- some upstream LSFG-Android bugs may still exist

These areas may improve in future releases.

---

## Troubleshooting

If frame generation does not work correctly, include the following when
reporting the problem:

```text
Device model:
SoC:
GPU:
Android version:
Stock ROM / Custom ROM / GSI:
Vulkan driver information:
LSFG multiplier:
Performance Mode status:
Real FPS:
Generated FPS:
Total FPS:
```

Logs are also useful when available.

---

## Credits

This project would not exist without the work of:

### FrankBarretta

[FrankBarretta/LSFG-Android](https://github.com/FrankBarretta/LSFG-Android)

Creator of the Android port and its Android-specific infrastructure,
including the MediaProjection capture pipeline, JNI/Vulkan integration,
AHardwareBuffer sharing, overlay system, UI and Android frame-generation
implementation.

### PancakeTAS & lsfg-vk contributors

[PancakeTAS/lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)

Authors and contributors of the original `lsfg-vk` Vulkan frame-generation
project that provides the foundation for this Android port.

### THS / Lossless Scaling

Original creators of **Lossless Scaling** and its frame-generation technology.

Lossless Scaling assets are **not distributed** by this repository.

---

## Upstream

This project is based on:

- [FrankBarretta/LSFG-Android](https://github.com/FrankBarretta/LSFG-Android)
- [PancakeTAS/lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)

Please support the original projects and contributors.

---

## License

This repository is a modified fork of LSFG-Android.

The repository contains components under different licenses:

- `LSFG-Android-Application/` — GNU General Public License v3.0
- `lsfg-vk-android/` — MIT License
- top-level repository files — MIT License

Always preserve the applicable license files, copyright notices and
attribution when redistributing or modifying the project.

`Lossless.dll` and other proprietary Lossless Scaling assets are **not**
distributed by this project.

---

<div align="center">

## LSFG Android — A6xx Compatibility

**Frame Generation • Android • Vulkan • A6xx**

Experimental compatibility work for Qualcomm A6xx GPUs.

</div>
