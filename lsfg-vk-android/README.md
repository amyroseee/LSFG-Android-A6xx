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
[`LSFG-Android-Application`](../LSFG-Android-Application/)

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

```

The frame-generation shaders themselves remain part of the LSFG pipeline.

The Android-specific work is mainly responsible for getting frames into and
out of that pipeline safely.

---

## Why AHardwareBuffer?

The original Linux implementation uses file-descriptor based external-memory
sharing.

That path cannot simply be reused for Android buffers.

The Android backend instead imports `AHardwareBuffer` objects directly into
Vulkan using:

```text
VK_ANDROID_external_memory_android_hardware_buffer
```

The application passes `AHardwareBuffer*` objects directly to the framegen
backend.

Those buffers are imported as Vulkan images and then used as LSFG inputs and
outputs.

This avoids depending on the Linux FD-based sharing path.

---

## Android API

The Android branch exposes additional entry points for the application.

### `createContextFromAHB`

```cpp
LSFG_3_1::createContextFromAHB(...)
LSFG_3_1P::createContextFromAHB(...)
```

Creates the frame-generation context directly from caller-provided
`AHardwareBuffer` inputs and outputs.

Instead of passing external-memory file descriptors, the Android application
provides the buffers themselves.

---

### `waitIdle`

```cpp
LSFG_3_1::waitIdle()
LSFG_3_1P::waitIdle()
```

Provides synchronization access to LSFG's internal Vulkan device.

This is necessary because the Android application and framegen backend can
operate using separate Vulkan devices while sharing the same AHardwareBuffers.

---

### AHardwareBuffer-backed `Core::Image`

The Android path adds support for creating a `Core::Image` directly from an:

```cpp
AHardwareBuffer*
```

The buffer is imported into Vulkan and used as a normal image by the
frame-generation pipeline.

---

## A6xx Vulkan Compatibility

Older A6xx drivers can expose a different Vulkan feature set compared with
newer Adreno hardware.

The Android fork therefore avoids treating some newer functionality as an
automatic hard requirement when a safe fallback exists.

The current A6xx path includes compatibility work around:

- Vulkan 1.1-class devices
- feature probing at runtime
- synchronization fallbacks
- binary semaphore / fence paths when required
- Android AHardwareBuffer imports
- descriptor fallback behavior
- older Qualcomm Vulkan implementations

The goal is capability-based compatibility rather than simply checking the
GPU model.

> [!IMPORTANT]
> A GPU belonging to the A6xx family does not automatically guarantee
> compatibility.
>
> OEM Vulkan drivers, Android versions and vendor implementations can still
> behave differently.

---

## Frame Generation Pipeline

Once the Android buffers are imported, the normal LSFG shader chain can
operate on them.

Conceptually:

```text
Captured Frame A
Captured Frame B
      ↓
Motion / Flow Analysis
      ↓
LSFG Shader Pipeline
      ↓
Generated Frame(s)
      ↓
AHardwareBuffer Output
      ↓
Presentation
```

The same frame-generation implementation is reused while the Android-specific
code handles memory sharing and synchronization.

---

## Main Android Changes

The Android work touches several areas of the original `lsfg-vk` tree.

| Area | Android change |
|---|---|
| Public LSFG API | Adds `createContextFromAHB(...)` and `waitIdle()` |
| Context | Adds AHardwareBuffer-based context creation |
| Core Image | Imports `AHardwareBuffer` into Vulkan |
| Generate stage | Supports pre-allocated Android output images |
| Vulkan device | Android-specific extension and capability handling |
| Synchronization | Android-compatible synchronization paths |
| Build system | Allows the Android app to consume `framegen/` through CMake |

---

## Repository Integration

This module is not normally built as a standalone Android application.

It is consumed by:

```text
../LSFG-Android-Application/
```

The Android Studio project pulls the native frame-generation sources using
CMake:

```cmake
add_subdirectory(...)
```

The expected repository layout is:

```text
Repository/
├── LSFG-Android-Application/
│   └── Android application
│
└── lsfg-vk-android/
    └── Native frame-generation backend
```

Keep both directories in their expected relative locations.

---

## Linux Compatibility

Android-specific functionality is isolated from the normal Linux path.

Android code is guarded where appropriate with:

```cpp
#ifdef __ANDROID__
```

The goal is to preserve the upstream Linux behavior while allowing the same
frame-generation codebase to expose additional functionality when compiled
for Android.

The traditional FD-based public API remains separate from the Android
AHardwareBuffer entry points.

---

## Main Components

Important native areas include:

```text
framegen/public/
framegen/include/core/
framegen/src/core/
framegen/v3.1_include/
framegen/v3.1_src/
framegen/v3.1p_include/
framegen/v3.1p_src/
thirdparty/
```

### Public API

Contains the LSFG public interfaces, including the Android-specific
AHardwareBuffer entry points.

### Core

Contains Vulkan device, image, memory and resource management.

### v3.1 / v3.1p

Contains the LSFG frame-generation contexts and shader pipeline.

### Third-party

Upstream dependencies used by the native backend.

---

## Third-party Components

The native project inherits several dependencies from upstream `lsfg-vk`,
including:

- [`volk`](https://github.com/zeux/volk)
- [`pe-parse`](https://github.com/trailofbits/pe-parse)
- DXVK `dxbc`
- `toml11`

These components remain part of the native LSFG build.

---

## Development Notes

Changes to this module can directly affect:

- Vulkan initialization
- AHardwareBuffer imports
- synchronization
- generated-frame output
- frame pacing
- compatibility across different GPU drivers

Because of this, native changes should be tested on physical hardware whenever
possible.

For A6xx compatibility testing, useful information includes:

```text
Device:
SoC:
GPU:
Android version:
Vulkan API:
Vulkan driver:
Framegen mode:
Result:
```

---

## Known Limitations

Android GPU behavior can vary significantly between:

- OEMs
- Android releases
- stock ROMs
- custom ROMs
- GSIs
- vendor Vulkan drivers

A configuration that works correctly on one A6xx device may require additional
compatibility work on another.

The current confirmed A6xx reference device is **Adreno 619**.

---

## Credits

This project builds directly on the work of:

### PancakeTAS

[`PancakeTAS/lsfg-vk`](https://github.com/PancakeTAS/lsfg-vk)

Creator of the original Linux `lsfg-vk` Vulkan frame-generation project and
the foundation of this native backend.

### FrankBarretta

[`FrankBarretta/LSFG-Android`](https://github.com/FrankBarretta/LSFG-Android)

Creator of the Android port and its native Android integration, including
AHardwareBuffer-based frame sharing and the Android LSFG application.

### THS / Lossless Scaling

Creators of **Lossless Scaling** and its frame-generation technology.

Lossless Scaling assets are not distributed by this repository.

---

## License

This module is derived from the upstream `lsfg-vk` project and is distributed
under the **MIT License**.

Preserve all original copyright notices, attribution and applicable license
files when modifying or redistributing this code.

See [`LICENSE.md`](LICENSE.md) for the complete license terms.

`Lossless.dll` and proprietary Lossless Scaling assets are **not distributed**
as part of this project.

---

<div align="center">

## lsfg-vk Android — A6xx Compatibility

**Vulkan • AHardwareBuffer • Native Frame Generation • A6xx**

Built on top of `lsfg-vk`.

</div>
