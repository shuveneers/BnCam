# Phase 2 — RAW preview input transport

Phase 2 was performed in the existing `main` worktree at
`daf9b5a2f3d50572917a3d1ae0b06dcaa3e54f31`. Phase 1 changes remain uncommitted.
No commit, push, repository reset, capture-quality change, or Phase 3 scheduling change was made.

## Transport decision

Before this phase, ordinary Camera2 RAW AHardwareBuffers were mapped on the CPU and a whole
declared frame was copied into a reused, persistently mapped Vulkan staging buffer. If that
memory was not device-local, Vulkan also copied it to a device buffer. The existing direct
VkBuffer import was restricted to BLOB allocations with `GPU_DATA_BUFFER` usage.

That BLOB path is retained: it can submit directly only when its byte-addressable AHB shape,
foreign queue-family support, external-buffer importability, memory type, allocation, bind,
and Vulkan submission succeed. The AHB is acquired after a successful import and released
after submission completion, or on an unsubmitted error. A failed import falls back to staging
if the input is CPU-readable. Ordinary RAW10 and RAW_SENSOR image AHBs do **not** gain a direct
path simply because the Vulkan AHB extension exists.

For image AHBs with GPU usage, the backend now probes `vkGetAndroidHardwareBufferPropertiesANDROID`,
the reported `VkFormat`/`externalFormat` and feature flags, and external image-format properties
when a defined VkFormat exists. It records a precise rejection reason. It still uses staging:
neither an external-format result nor sampled/transfer features prove that the shader sees the
original packed RAW10 or RAW_SENSOR CFA bytes, and the present producer/Image synchronization
contract does not establish safe direct image ownership. CPU-only AHBs are rejected before a
Vulkan AHB-properties query, which requires GPU usage. A future direct image route needs a
per-device/per-frame proof of exact sample representation and producer acquire/release fences.

The fallback now verifies AHB identity, dimensions, format, CPU-read usage and unprotected
status. `lockPlanes` supplies the actual source row/pixel stride; invalid or mismatched layouts
fail closed. Only a failed plane lock can use flat-lock compatibility, and only if the AHB's
pixel stride corroborates Camera2's declared byte row stride. The source request is never
mutated. One row-wise CPU copy transfers visible bytes into the reused mapped staging buffer,
zeroes destination padding, and preserves the Camera2 layout expected by the shader. Capacity
and arithmetic are checked. The existing device-local host-visible staging avoids a second
GPU copy when available.

`RawPreviewInput` logs the actual transport, AHB/Vulkan formats and features, rejection,
copied byte counts, packing time, import retain/release counters, and failure at most once per
two seconds. The redundant per-frame GPU failure log was removed; the existing failure logger
is rate-limited. A still-running preview submission now blocks reuse of shared staging and
other mutable compute inputs, and runtime shutdown defers destruction until submitted preview
fences finish. This bounds ownership if a fence times out; the full slot ownership and preview
cadence work belongs to Phase 3.

## Device evidence

The connected BKQ_N49 (`AUWE025B03006422`) ran the installed debug APK with live RAW_SENSOR
preview. Its AHB reports `format=32`, `usage=0x20003`: CPU-read usage and **no GPU usage**.
The observed path was `HOST_MAPPED_AHB_STAGING`, rejection `AHB_HAS_NO_GPU_USAGE`,
`cpuBytes=25165824`, `gpuCopyBytes=0`, and packing about 2–13 ms in sampled log entries
(usually about 2–4 ms after startup).
The direct-import retain/release counters stayed 0/0, as expected for this path. Vulkan
format/external format are unqueried (both zero), and image query status is `VK_NOT_READY`.
RAW10 copy layout passed the native test, but this session did not measure a RAW10 runtime AHB.
There was no camera image AHB on this device for which direct image import could be proven.

Repeated `GPU_PREVIEW_COMPLETION_TIMEOUT_DROPPED` entries remain visible in the rate-limited
diagnostic. They occur after the staging copy and are not evidence that this copy caused
the user's long first cold still-processing delay. That delay was not timed end-to-end in this
phase and should not be described as fixed. Preview completion/scheduling is Phase 3 scope.

## Validation and limits

- `assembleDebug` passed, including the arm64-v8a native build, after the final Vulkan query
  and stride checks.
- `RawPreviewInputPolicyTest.cpp` compiled with NDK 27 Clang at `-Wall -Wextra -Werror` and
  passed on the connected arm64 device. It covers eligible/ineligible BLOB contracts, both RAW
  row-copy formats, distinct source/destination strides, padding, bad dimensions/pixel stride,
  insufficient capacity and arithmetic overflow.
- `testDebugUnitTest assembleDebug --continue` built the APK but the repository JVM suite is
  not green: 1,912 tests, 355 failures. Phase 1 already recorded 354 failures on this baseline.
  Existing `Phase6RawPreviewGpuResidencySourceContractTest` assertions for the former literal
  BLOB guard and whole-frame `memcpy` are stale after this intentional change; other unrelated
  failures were not individually baseline-reproduced. No tests were disabled or rewritten to
  mirror the implementation.
- The real BLOB direct-import success/failure path, GPU-usage camera image probing, generation
  switch under an outstanding imported input, and retain/release balance for a direct input
  require compatible AHB hardware or an instrumented Vulkan/Camera2 integration fixture. The
  tested device exercised the unsupported direct path, staging fallback, and live preview;
  the direct input counters remained balanced at 0/0. Shutdown has a nonblocking fence guard,
  but no on-device forced in-flight shutdown scenario was run.

Relevant API contracts: [Vulkan AHB external memory](https://docs.vulkan.org/spec/latest/chapters/memory.html),
[AHB properties query](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetAndroidHardwareBufferPropertiesANDROID.html),
[AHB format properties](https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferFormatPropertiesANDROID.html),
and [Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer).

Phase 2 ends here. No Phase 3 changes have been started.

Three temporary local validation artifacts remain untracked under `work/`:
`phase2-device-screen.png`, `phase2-device-ui.xml`, and
`raw_preview_input_policy_test`. An automatic approval review rejected their deletion.
