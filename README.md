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
