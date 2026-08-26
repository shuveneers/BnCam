# Phase 2 compute boundary truth

## Production backend

Production capture currently executes on native CPU/OpenCV. Alignment, RAW/YUV accumulation,
demosaic and ISP work are not Vulkan operations. `CaptureRecipe.computeBackendId` resolves through
`ComputeBackendRegistry` to `cpu_native_opencv`.

The backend-neutral types in `core/compute/ComputeBackend.kt` define the future insertion boundary:
image ownership, graph nodes, execution results, explicit crossings and capabilities. No GLES
function is presented as an implementation of that interface.

## Existing GLES entry points

`GpuIsp.cpp` is OpenGL ES 3.1 compute over EGL:

- `GpuIsp::initializeEngine()` creates a persistent EGL display/context/pbuffer and compiles four
  highlight-recovery programs.
- `GpuIsp::releaseEngine()` deletes those programs and releases EGL resources.
- `GpuIsp::applyBentoHighlightRecovery(cv::Mat&)` requires the persistent engine, but creates and
  deletes five textures and one framebuffer for each call.
- `GpuIsp::processToBgr(...)` is a standalone per-call path. It creates an EGL display, context,
  pbuffer, compute shader, program, two textures and framebuffer on every call, then destroys them.
- JNI currently exposes only engine initialize/release. Static inspection found no production call
  to either `processToBgr` or `applyBentoHighlightRecovery`.

`BnCameraManager` still initializes and releases the legacy engine with application/camera
lifecycle. That is lifecycle overhead, not proof that production image processing uses GLES.

## CPU/GPU crossings

`applyBentoHighlightRecovery`:

1. OpenCV `CV_32FC3` RGB is converted on CPU to `CV_32FC4`.
2. `glTexSubImage2D` uploads the full float image to `GL_RGBA32F`.
3. GLES compute writes intermediate and final textures.
4. A per-call framebuffer plus `glReadPixels` reads the full `GL_RGBA32F` result to CPU.
5. OpenCV converts the result back to three-channel RGB.

`processToBgr`:

1. OpenCV input is made contiguous; 8-bit BGR input is expanded to BGRA on CPU.
2. `glTexImage2D` uploads `GL_RGBA8UI` or `GL_RGB16UI`.
3. GLES compute writes `GL_RGBA8`.
4. A per-call framebuffer plus `glReadPixels` reads RGBA8 to CPU.
5. OpenCV converts RGBA8 to BGR8.

There is no `AHardwareBuffer` import, persistent image pool, exported GPU image, asynchronous graph,
or explicit cross-API synchronization in these GLES paths.

## Vulkan preparation state

Phase 3A-Prep now provides the application/native-engine runtime owner, typed JNI boundary,
validation collection, VMA integration point, resource ownership contracts and diagnostic exports.
The only real bootstrap injection point is
`app/src/main/cpp/vulkan/VulkanRuntimeBootstrap.cpp`.

Vulkan is the mandatory future production backend. No CPU-versus-Vulkan comparison framework is
required. The current CPU/OpenCV route remains active only until its stages are replaced. The real
runtime must reach READY and remain persistent before any image stage is migrated.

See `docs/VULKAN_INJECTION_HANDOFF.md`.
