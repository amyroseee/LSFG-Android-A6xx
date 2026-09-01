<div align="center">

# lsfg-vk Android — A6xx Compatibility

<img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=20&pause=1000&center=true&vCenter=true&width=650&lines=Native+LSFG+Backend+for+Android;AHardwareBuffer+%2B+Vulkan;A6xx+Compatibility;Built+on+lsfg-vk" />

<br>

![Android](https://img.shields.io/badge/Platform-Android-brightgreen?logo=android)
![Vulkan](https://img.shields.io/badge/API-Vulkan-red)
![Native](https://img.shields.io/badge/Native-C%2B%2B-blue)
![A6xx](https://img.shields.io/badge/Compatibility-A6xx-purple)
![Upstream](https://img.shields.io/badge/Upstream-lsfg--vk-orange)

### Native frame-generation backend used by LSFG Android

Android-specific compatibility work built on top of
[`PancakeTAS/lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk).

</div>

---

## About

This module is the native frame-generation backend used by
[`LSFG-Android`](../LSFG-Android/).

It is based on [`lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk) and adds
Android-specific Vulkan integration required for frame generation through
`AHardwareBuffer`.

The Android implementation allows captured frames to be shared between the
Android application and LSFG's internal Vulkan device without relying on the
Linux file-descriptor image-sharing path.

The Linux code path remains separate from the Android-specific implementation.

---

## A6xx Compatibility

This fork includes additional compatibility work focused on Qualcomm
**A6xx GPUs**.

The current implementation has been physically tested with:

| SoC | GPU | Status |
|---|---|---|
| Snapdragon 695 | Adreno 619 | ✅ Working |

The compatibility work includes runtime fallbacks for Vulkan capabilities
that may not be available on older Qualcomm drivers.

This allows LSFG to operate on hardware where the original Android backend
could fail during Vulkan initialization.

> [!NOTE]
> Other A6xx GPUs may also work, but compatibility is still experimental
> until tested on physical devices.

---

## Android Frame Path

On Linux, `lsfg-vk` can operate as a Vulkan layer directly in the target
application.

Android has different platform restrictions, so LSFG Android uses a separate
capture and presentation architecture.

```text
Game
 ↓
MediaProjection
 ↓
AHardwareBuffer
 ↓
Host Vulkan Session
 ↓
lsfg-vk Android
 ↓
Frame Generation
 ↓
Generated AHardwareBuffers
 ↓
Android Overlay
