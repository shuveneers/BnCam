# BnCam Vulkan Injection Handoff

Status: **architecture prepared; real Vulkan bootstrap not injected**  
Baseline: approved Phase 1A, 1B, 2 and 2C

## Product decision

Vulkan is the mandatory future production compute backend. The preparation layer does not debate
that decision, does not create a CPU-comparison gate, and does not present GLES as Vulkan.

Current image execution remains CPU/OpenCV until actual Vulkan stages replace it. This is temporary
continuity, not a permanent fallback architecture.

## Exact ownership

There is one authoritative native owner:

```text
BnCamApplication (process lifecycle)
  -> NativeEngineLoader (loads native libraries exactly once)
  -> VulkanRuntimeOwner (Kotlin metadata/control facade)
  -> VulkanNativeBridge (typed JNI, no raw handles)
  -> bncam::vulkan::VulkanRuntime (only authoritative native owner)
  -> VulkanRuntimeBootstrap (only real implementation injection point)
```

Forbidden owners:

- Compose;
- `CameraScreen`;
- `BnCameraManager`;
- Single/Multi Frame runners;
- a camera session;
- a capture attempt;
- a capability query;
- a benchmark.

`VulkanRuntime` is a function-local native singleton. Initialization is thread-safe and idempotent.
Captures may later borrow the runtime through typed submissions but may never create or destroy it.

## Creation point

`BnCamApplication.onCreate()` now:

1. loads `c++_shared`, `opencv_java4`, and `bncam` through `NativeEngineLoader` exactly once;
2. attaches `VulkanRuntimeOwner` to the application process.

The next agent adds the real call immediately after attachment:

```kotlin
VulkanRuntimeOwner.initialize(
    VulkanRuntimeConfig(
        debugValidationRequested = BuildConfig.DEBUG,
        requireAndroidHardwareBuffer = true
    )
)
```

Do not place that call in a camera/session/capture object.

## Destruction point

The runtime is process-scoped. Ordinary Android process death does not provide a reliable
`Application.onTerminate()` callback; the OS reclaims process resources. Deterministic shutdown is
still implemented through `VulkanRuntimeOwner.shutdown()` and JNI for:

- instrumentation;
- emulator lifecycle;
- controlled runtime tests;
- explicit future native-engine shutdown.

`BnCamApplication.onTerminate()` invokes the controlled hook when Android actually calls it.
Activity or camera shutdown must not destroy Vulkan.

Required native destruction order is fixed in `VulkanRuntimeBootstrap.h`:

1. stop submissions and wait boundedly for in-flight work;
2. destroy pipelines and future resource pools;
3. destroy pipeline cache, descriptor pool and command pool;
4. destroy VMA;
5. destroy logical device;
6. destroy debug messenger;
7. destroy Vulkan instance.

## Runtime state contract

Stable native/Kotlin states and codes:

```text
0 UNINITIALIZED
1 INITIALIZING
2 READY
3 UNAVAILABLE
4 FAILED
5 SHUTTING_DOWN
6 DESTROYED
```

A runtime may report `READY` only when all mandatory ownership exists:

- `VkInstance`;
- selected `VkPhysicalDevice`;
- `VkDevice`;
- compute queue and family;
- persistent command pool;
- persistent descriptor pool;
- persistent pipeline cache;
- ready VMA allocator.

The preparation bootstrap returns `VULKAN_BOOTSTRAP_NOT_INJECTED`; it never returns fake success.

## Files to implement first

The next agent should edit these files first and avoid broad project analysis:

1. `app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp`
2. `app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.h` only when additional owned handles are needed
3. `app/src/main/cpp/vulkan/VulkanRuntimeContracts.*` to populate scanned capability values
4. `app/src/main/java/com/bncam/BnCamApplication.kt` to call `initialize()` after attachment

The owner, JNI boundary, diagnostics, validation collector, CMake source registration, VMA adapter,
resource contracts and trace export are already prepared.


## Exact current processing injection map

The next agent does not need to rediscover the current route boundaries:

| Future Vulkan boundary | Current authoritative call path | Current native implementation |
|---|---|---|
| Application runtime creation | `BnCamApplication.onCreate()` → `VulkanRuntimeOwner.initialize(...)` | `VulkanRuntimeBootstrap::initialize(...)` |
| Application/runtime shutdown | `VulkanRuntimeOwner.shutdown()` | `VulkanRuntime::shutdown()` → `VulkanRuntimeBootstrap::destroy(...)` |
| Single/Multi YUV render | `ImageUtils.processNativeYuvSafe(...)` | `Java_com_bncam_core_engine_ImageUtils_processNativeYuv` in `native-lib.cpp` |
| RAW10 master creation | `ImageUtils.mergeRaw10NativeRaw16Safe(...)` | `mergeNativeRaw10DirectRaw16` → `DngMerger.cpp` |
| RAW_SENSOR master creation | `ImageUtils.mergeRawSensorNativeRaw16Safe(...)` | `mergeNativeRawSensorDirectRaw16` → `DngMerger.cpp` |
| Single RAW JPEG | `SingleFrameRunner` → `ImageUtils.renderJpegFromRaw16InputSafe(...)` | `renderJpegFromMasterNative` → `IspCore::renderRawBaselineJpeg(...)` |
| Multi RAW JPEG | `MultiFrameRunner` → `ImageUtils.renderJpegFromMasterFrameSafe(...)` | same native RAW ISP entry |
| Multi RAW alignment/fusion | `MasterRawFrame` native master construction | `DngMerger.cpp` phase-correlation/RAW accumulation |
| Demosaic and RAW ISP | RAW master render JNI | `Demosaic.cpp` and `IspCore.cpp` |
| Legacy GLES | camera-manager warm-up/release | `GpuIsp.cpp`; disconnected from production capture |
| Trace export | `ShotLogger.writeCaptureTrace(...)` | prepared Vulkan JSON/text exports |

Do not inject unfinished Vulkan decisions directly into `SingleFrameRunner`, `MultiFrameRunner`,
`CameraScreen`, or `BnCameraManager`. Phase 3A changes only the runtime/bootstrap. Later stage
migrations should replace the listed native processing boundaries through dedicated Vulkan stage
owners.

## Native module map

| File | Responsibility |
|---|---|
| `VulkanRuntime.*` | singleton state machine, idempotence, counters, in-flight guard, shutdown |
| `VulkanRuntimeBootstrap.*` | only real instance/device/queue/pool/cache creation and destruction |
| `VulkanRuntimeContracts.*` | typed runtime and capability snapshots, stable exports |
| `VulkanValidationCollector.*` | bounded native validation records and deduplication |
| `VulkanDebugUtilsAdapter.*` | ready `VK_EXT_debug_utils` callback adapter |
| `VulkanVmaIntegration.*` | one VMA owner and bounded statistics |
| `VulkanResourceContracts.h` | future owned/imported image, buffer and submission ownership |
| `VulkanRuntimeJni.cpp` | stable metadata/control JNI; no handles or image payloads |
| `VulkanJson.*` | bounded stable native JSON helpers |

## VMA

Pinned dependency: AMD/GPUOpen Vulkan Memory Allocator **v3.3.0**.

Fetch once from the project root:

```powershell
powershell -ExecutionPolicy Bypass -File app/tools/fetch_vma.ps1
```

The script verifies the version marker. Commit/vendor the resulting
`app/src/main/cpp/third_party/vma-3.3.0/vk_mem_alloc.h` for offline reproducibility.

CMake detects the header. The transfer environment could not retrieve the pinned upstream file, so
the delta contains deterministic fetch tools, version metadata and license but not
`vk_mem_alloc.h`. Until fetched, builds remain possible and diagnostics report VMA absent. Real
bootstrap must not report `READY` without a ready allocator.

VMA owns normal BnCam buffers/images. Imported `AHardwareBuffer` memory remains externally owned and
uses explicit Android external-memory and dedicated-allocation rules.

## vk-bootstrap decision

`vk-bootstrap` is **not selected** for the first injection. The existing compute-only scope is small,
the project needs transparent feature/extension diagnostics, and the prepared bootstrap is already
narrow. Use direct `vulkan.h` in `VulkanRuntimeBootstrap.cpp`.

Do not add vk-bootstrap unless direct implementation becomes materially less maintainable. Never
create a second initialization path.

## Validation without Logcat

Debug builds already pass `BNCAM_VULKAN_VALIDATION_ENABLED=1`. Release passes `0`.

The debug-utils callback is prepared in `VulkanDebugUtilsAdapter.cpp`. It copies bounded messages to
`ValidationCollector`; it performs no file I/O, JNI, throwing, or direct Kotlin calls.

The real bootstrap must:

1. package a compatible arm64 `VK_LAYER_KHRONOS_validation` in the debug build/source set, then enumerate and enable it only when available;
2. enable `VK_EXT_debug_utils` when available;
3. create `VkDebugUtilsMessengerEXT` using `debugUtilsCallback` and the runtime collector;
4. continue truthfully if validation is unavailable but Vulkan itself works.

Shot diagnostics already export:

- `vulkan_runtime.json`;
- `vulkan_capabilities.json`;
- `vulkan_diagnostics.json`;
- `vulkan_validation.json`;
- `Vulkan Runtime.txt`;
- `Vulkan Capabilities.txt`;
- `Vulkan Validation.txt`.

Do not use `adb logcat`; Honor HKS makes it unusable.

## Capability scan contract

Populate all prepared capability records and distinguish:

```text
AVAILABLE
SUPPORTED
ENABLED
REQUIRED_LATER
MISSING
NOT_SCANNED
```

Scan at minimum loader/API versions, selected device/driver, compute queues, heaps/types, storage
formats, timestamps, subgroups, synchronization, descriptor indexing, external memory,
dedicated allocation, external semaphore/fence support, and
`VK_ANDROID_external_memory_android_hardware_buffer`.

Do not claim YUV/RAW importability from extension presence alone. Actual buffer-format property
queries occur when Phase 3B imports real `AHardwareBuffer` objects.

## AHardwareBuffer ownership

Prepared strategies:

- `DIRECT_IMPORT` when the actual buffer and format are importable;
- `NATIVE_LOCK_TO_PERSISTENT_STAGING` when direct import is unsupported;
- `UNSUPPORTED` when neither route is safe.

For YUV, RAW10 and RAW_SENSOR:

- retain/release external buffers explicitly;
- never introduce a Java RAW16 round-trip;
- use acquire/release fence ownership;
- respect dedicated allocation requirements;
- preserve RAW CFA layout and row/pixel stride metadata.

No capture buffer is imported in the preparation phase.

## Existing legacy paths

`GpuIsp` remains EGL/GLES 3.1 and remains disconnected from production image processing. Its
camera-scoped initialization/release is known legacy overhead. Do not copy this lifecycle for
Vulkan. Remove it only after proving no consumer remains in a later phase.

The old standalone Vulkan capability probe now delegates to `VulkanRuntime`; it no longer creates a
temporary instance. The former CPU-vs-GPU benchmark endpoint is retained only as a compatibility
endpoint and returns `RETIRED`. It cannot create Vulkan objects.

## Build and verification commands

After fetching VMA:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Host-only preparation check:

```powershell
python app/tools/run_vulkan_prep_host_tests.py
python app/tools/verify_vulkan_prep.py
```

## Required device proof for real injection

Without Logcat, prove through BnCam diagnostic exports:

- runtime reaches `READY`;
- physical and logical device are real;
- compute queue exists;
- VMA is ready;
- validation status is truthful;
- instance creation count is `1`;
- device creation count is `1`;
- multiple YUV/RAW10/RAW_SENSOR captures do not increment those counts;
- active Vulkan production stages remain empty in Phase 3A;
- controlled shutdown reaches `DESTROYED` without in-flight work.

Test at least Single YUV JPEG, Single RAW10 JPEG and Single RAW_SENSOR JPEG. Their image processing
may still be CPU/OpenCV in Phase 3A.

## Known remaining implementation work

Only the real bootstrap and device verification remain for Phase 3A:

- instance and debug messenger;
- physical-device selection;
- logical device and compute queue;
- command/descriptor pools;
- pipeline cache persistence policy;
- VMA creation;
- capability population;
- connected-device testing.

Do not start shaders or production-stage migration before Phase 3A runtime ownership reaches READY.
