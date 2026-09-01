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
