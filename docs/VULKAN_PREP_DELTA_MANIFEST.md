# BnCam Phase 3A-Prep delta manifest

Date: 2026-07-30

## Baseline

- Uploaded source archive: `gradlew.zip`
- SHA-256: `34571c7a38fb18e941ad848e2681adc5fe7e7f050769cb0d5ddc71515920283b`
- Baseline: approved post-Phase 2C source provided without the 2.6 GB local OpenCV module.

The delta does not remove, replace, or contain `opencv/`. Apply it over the original project root.

## Current truth after applying this delta

- One authoritative process/native-engine Vulkan runtime owner is defined.
- Kotlin/JNI/native ownership, diagnostics, validation collection, VMA integration point, resource contracts, and exact injection boundaries are prepared.
- The old capability probe no longer owns temporary Vulkan instances.
- The simulated Vulkan benchmark is retired and cannot create a competing device.
- Native loading is process-scoped and no longer duplicated through `OpenCVLoader.initDebug()`.
- Real instance/device/queue/pool/cache creation remains intentionally absent.
- Vulkan therefore remains `UNAVAILABLE` with `VULKAN_BOOTSTRAP_NOT_INJECTED`, never fake `READY`.
- Vulkan active production stages remain empty; existing CPU/OpenCV processing is untouched.

## Pinned dependency note

- VMA version: `3.3.0`.
- Upstream Git blob SHA: `8df03649b5b97acc1e43839d2857d32d267958ca`.
- License and metadata are included.
- The large single header is not included because it could not be materialized in this environment.
- `app/tools/fetch_vma.ps1` and `.sh` download and verify the exact pinned upstream blob.

## Verification classification

### Verified locally

- Native Vulkan preparation host tests.
- Validation collector behavior and bounds.
- Runtime state-machine transitions, concurrent initialization, shutdown idempotence, and raw-handle move ownership.
- JNI method parity and stable schemas.
- C++ syntax using isolated Android/Vulkan/JNI stubs.
- Kotlin ownership/bridge/compute contracts using Android API stubs.
- No competing `vkCreateInstance` or native library initialization path.
- Delta file inventory and whitespace checks.

### Not verified in this environment

- Android Gradle/NDK build: the uploaded source intentionally excluded the local `opencv/` Gradle module and no matching SDK/NDK is installed.
- Connected Honor runtime behavior.
- Actual Vulkan loader/device capabilities.
- Debug validation-layer packaging.
- Real Vulkan initialization and VMA creation.

No Android build or connected-device PASS is claimed.

## Delta contents

- `_APPLY_BNCAM_VULKAN_PREP_DELTA.txt`
- `app/build.gradle.kts`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/VulkanBenchmarkKernels.cpp`
- `app/src/main/cpp/VulkanCapabilities.cpp`
- `app/src/main/cpp/VulkanCapabilities.h`
- `app/src/main/cpp/third_party/vma-3.3.0/LICENSE.txt`
- `app/src/main/cpp/third_party/vma-3.3.0/README.md`
- `app/src/main/cpp/third_party/vma-3.3.0/VERSION.txt`
- `app/src/main/cpp/vulkan/VulkanDebugUtilsAdapter.cpp`
- `app/src/main/cpp/vulkan/VulkanDebugUtilsAdapter.h`
- `app/src/main/cpp/vulkan/VulkanJson.cpp`
- `app/src/main/cpp/vulkan/VulkanJson.h`
- `app/src/main/cpp/vulkan/VulkanResourceContracts.h`
- `app/src/main/cpp/vulkan/VulkanRuntime.cpp`
- `app/src/main/cpp/vulkan/VulkanRuntime.h`
- `app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp`
- `app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.h`
- `app/src/main/cpp/vulkan/VulkanRuntimeContracts.cpp`
- `app/src/main/cpp/vulkan/VulkanRuntimeContracts.h`
- `app/src/main/cpp/vulkan/VulkanRuntimeJni.cpp`
- `app/src/main/cpp/vulkan/VulkanRuntimeState.h`
- `app/src/main/cpp/vulkan/VulkanValidationCollector.cpp`
- `app/src/main/cpp/vulkan/VulkanValidationCollector.h`
- `app/src/main/cpp/vulkan/VulkanVmaForward.h`
- `app/src/main/cpp/vulkan/VulkanVmaIntegration.cpp`
- `app/src/main/cpp/vulkan/VulkanVmaIntegration.h`
- `app/src/main/java/com/bncam/BnCamApplication.kt`
- `app/src/main/java/com/bncam/MainActivity.kt`
- `app/src/main/java/com/bncam/core/compute/ComputeBackend.kt`
- `app/src/main/java/com/bncam/core/debug/ShotLogger.kt`
- `app/src/main/java/com/bncam/core/engine/ImageUtils.kt`
- `app/src/main/java/com/bncam/core/nativebridge/NativeEngineLoader.kt`
- `app/src/main/java/com/bncam/core/vulkan/VulkanNativeBridge.kt`
- `app/src/main/java/com/bncam/core/vulkan/VulkanRuntimeModels.kt`
- `app/src/main/java/com/bncam/core/vulkan/VulkanRuntimeOwner.kt`
- `app/src/test/cpp/VulkanRuntimePrepHostTest.cpp`
- `app/src/test/cpp/VulkanValidationCollectorHostTest.cpp`
- `app/src/test/java/com/bncam/core/compute/ComputeBackendRegistryTest.kt`
- `app/src/test/java/com/bncam/core/nativebridge/NativeEngineLoaderSourceContractTest.kt`
- `app/src/test/java/com/bncam/core/vulkan/VulkanJniContractTest.kt`
- `app/src/test/java/com/bncam/core/vulkan/VulkanPreparationSourceContractTest.kt`
- `app/src/test/java/com/bncam/core/vulkan/VulkanRuntimeModelsTest.kt`
- `app/tools/fetch_vma.ps1`
- `app/tools/fetch_vma.sh`
- `app/tools/run_vulkan_prep_host_tests.py`
- `app/tools/verify_vulkan_prep.py`
- `docs/PHASE2_COMPUTE_BOUNDARY.md`
- `docs/THIRD_PARTY_VULKAN.md`
- `docs/VULKAN_INJECTION_HANDOFF.md`
- `docs/VULKAN_NEXT_AGENT_PROMPT.md`
- `docs/VULKAN_PREP_DELTA_MANIFEST.md`
- `docs/VULKAN_PREP_IMPLEMENTATION_REPORT.md`

## Apply and continue

See `_APPLY_BNCAM_VULKAN_PREP_DELTA.txt`, then hand the project to Antigravity with `docs/VULKAN_NEXT_AGENT_PROMPT.md`.
