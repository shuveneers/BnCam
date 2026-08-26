# BnCam Phase 3A — Pure Vulkan Bootstrap Injection

Work in the current project. Phase 1A/1B/2/2C and Phase 3A-Prep are approved.

Do not re-analyze or redesign ownership. Read `docs/VULKAN_INJECTION_HANDOFF.md` and implement only
the real runtime behind `app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp`.

Vulkan is mandatory. Do not build CPU-parity infrastructure, do not use GLES as Vulkan, and do not
use/request Logcat because Honor HKS blocks useful output.

## Controlled execution

Complete one block, build/test, report briefly, then stop until the user says `CONTINUE`.

### Block 1 — Bootstrap implementation

1. Run `app/tools/fetch_vma.ps1`; vendor the pinned VMA 3.3.0 header.
2. Package the compatible arm64 Khronos validation layer in the debug source set; release must not contain or require it.
3. Implement direct `vulkan.h` instance creation, debug layer/utils negotiation, physical-device
   selection, compute queue, logical device, persistent command pool, descriptor pool, pipeline
   cache and VMA.
4. Populate the prepared capability snapshot truthfully.
5. Implement the documented reverse destruction order and clear every owned handle before reporting success.
6. Route `VK_EXT_debug_utils` into the existing bounded validation collector.
7. Do not connect image stages and do not edit camera/runners.

Build and run unit/static checks. Stop and report.

### Block 2 — Application activation and Honor verification

After `CONTINUE`, call `VulkanRuntimeOwner.initialize(...)` in `BnCamApplication.onCreate()` directly
after attachment. Do not initialize elsewhere.

On the connected Honor, without Logcat, verify READY, device/queue/VMA, diagnostic exports,
instance/device counts of one across multiple captures, YUV/RAW10/RAW_SENSOR Single JPEG capture,
and deterministic controlled shutdown. `activeProductionStages` must remain empty.

Stop and report. Do not start Phase 3B.
