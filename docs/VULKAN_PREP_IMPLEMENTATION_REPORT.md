# Phase 3A-Prep implementation report

## Result

BnCam is prepared for direct persistent Vulkan runtime injection without changing capture routes or
image-processing mathematics.

## Added architecture

- one process-scoped native `VulkanRuntime` owner;
- stable runtime states, identity and creation counters;
- thread-safe idempotent initialization/shutdown state machine;
- bounded in-flight submission guard;
- one implementation injection point: `VulkanRuntimeBootstrap`;
- typed capability and failure contracts;
- typed JNI metadata/control boundary with no raw handles;
- application-scoped Kotlin owner;
- process-scoped native library loader;
- bounded, non-blocking validation collector and ready debug-utils callback;
- VMA v3.3.0 integration and fetch tooling;
- AHardwareBuffer/resource/submission ownership contracts;
- structured and human-readable Vulkan shot exports;
- host/static verification tools.

## Existing competing paths corrected

- the old capability query no longer creates a temporary Vulkan instance;
- the old simulated CPU-vs-GPU benchmark cannot create an instance/device and reports `RETIRED`;
- Vulkan initialization is forbidden in camera, runner and Compose ownership;
- native library loading now has one process-scoped source of truth; the duplicate `OpenCVLoader.initDebug()` path was removed.

## Current truthful state

- CPU/OpenCV: active production processing;
- GLES: disconnected legacy with camera-scoped lifecycle overhead;
- Vulkan: architecture prepared, bootstrap not injected;
- Vulkan production stages: none;
- VMA: pinned to v3.3.0, adapter/fetch tooling ready, and the fetch validates the exact upstream Git blob; the large upstream header still needs one deterministic fetch because this transfer environment could not materialize it locally;
- device verification: not performed in this environment.

## Locally verified

- validation buffer capacity, message truncation, object truncation, deduplication, repeat counts,
  dropped-message accounting, JSON and human rendering;
- source contracts for application ownership, no per-capture initialization, no competing
  `vkCreateInstance`, JNI name parity, VMA pinning and validation callback safety;
- exact modified/additional file inventory for delta packaging;
- C++ runtime state-machine behavior, handle move semantics, JNI/source parity and native syntax;
- Kotlin ownership/bridge/compute contracts through host compilation with Android API stubs.

## Not verifiable here

- Android Gradle/NDK build because the uploaded project intentionally excluded the local OpenCV
  Gradle module and this environment has no matching Android SDK/NDK installation;
- connected Honor runtime behavior;
- actual validation-layer packaging;
- real device capabilities.

No device or build PASS is claimed.
