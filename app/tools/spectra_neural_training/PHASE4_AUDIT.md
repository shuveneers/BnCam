# SPECTRA Neural Phase 4 — final audit

Baseline: N006R + N007A-E + N008A-F + N009A-F + N010A-E.

## Scope result

Phase 4 implements the Vulkan neural inference backend contract without connecting it to the production single-frame ISP callsite. Production placement before demosaic, SPECTRA Core capture wiring, OOD policy and app/profile integration remain Phase 5/6 work.

## Masterprompt task closure

1. **Model loader — COMPLETE (source/offline validated).** `VulkanNeuralModelPackage` parses the frozen Vulkan package and fails closed on schema, bounds, alignment and SHA mismatch.
2. **Weight packing — COMPLETE.** Phase-3 FP16 weights are repacked deterministically to C4-compatible little-endian storage with 16-byte tensor alignment and frozen package hashes.
3. **Activation layout — COMPLETE.** `VulkanNeuralTensorLayout` defines C4 FP16 storage, original/padded image geometry and byte sizing.
4. **3x3 / 1x1 convolution primitives — COMPLETE.** `neural_conv.comp` implements both through one generic kernel with FP16 storage and FP32 accumulation.
5. **Gating primitives — COMPLETE.** `neural_gate.comp` implements the Student SimpleGate contract.
6. **Downsample / upsample — COMPLETE.** Strided convolution owns downsampling; nearest-2x sampling is fused into convolution for upsampling.
7. **FiLM / stage modulation — COMPLETE.** `neural_film_params.comp` and `neural_film_apply.comp` implement global physics-conditioned modulation.
8. **Tiled inference — COMPLETE.** `VulkanNeuralExecutionPlan` emits deterministic valid-center tiles and the concrete backend records all graph operations per tile.
9. **Halo / valid-center publication — COMPLETE.** Halo derives from the model receptive field, is aligned to the Student 8-pixel phase, and edge tiles clip to the real padded image boundary.
10. **On-the-fly conditioning — COMPLETE.** `neural_condition.comp` constructs the frozen 14-channel physics conditioning from RAW/S/O/remaining-LSC/trust/headroom inputs on GPU.
11. **Persistent allocations — COMPLETE.** Weights and 2-4 in-flight activation slots are persistent VMA resources; descriptor sets are reused across tiles.
12. **Async scheduling — COMPLETE.** `submitAsync()` records/submits fence-backed work and `resolve()` waits without holding the backend mutex across the GPU wait.
13. **GPU-side RAW input path — COMPLETE.** Resident `VkBuffer` is the primary input contract.
14. **Capability-driven AHardwareBuffer import — COMPLETE.** Direct Android BLOB import requires GPU-data-buffer usage, producer synchronization and Vulkan external-memory import capability.
15. **GPU staging fallback — COMPLETE.** A BnCam-owned Vulkan buffer may be supplied as the fallback. No CPU pixel fallback exists.
16. **Fused kernels — COMPLETE.** Nearest upsample is fused into convolution and clipping/headroom evidence publication is fused into final writeback; there is no separate highlight-reconstruction kernel.
17. **Exact golden-vector parity — COMPLETE for package/graph/tile reference; DEVICE GATE OPEN.** The packed `.vkmodel` reproduces the frozen Phase-3 golden outputs exactly and tiled valid-center reference equals full-frame reference exactly. Actual compiled SPIR-V execution must still be run on an Android Vulkan device because this environment has no NDK `glslc`, Vulkan device or Android driver.
18. **No full-frame CPU fallback — COMPLETE.** Source audit rejects AHardwareBuffer CPU locks, OpenCV RAW fallback and host full-frame RAW readback.

## User authority / bypass boundary

The Phase-4 backend already honors the future profile contract:

- `enabled=false` -> no resource import, no model dispatch, identity result;
- `Noise Reduction <= epsilon` -> the same no-dispatch identity path;
- non-zero authority is applied only to the already sigma-bounded neural residual in writeback;
- no hidden minimum denoise authority is introduced.

Profile persistence/UI wiring remains Phase 5/6 and is intentionally not implemented here.

## Future Neural Highlight Reconstruction boundary

SPECTRA Neural Denoise remains noise-suppression-only. Phase 4 preserves original saturation mask/headroom evidence as optional GPU outputs. It does not reconstruct clipped signal. This reserves a separate future RAW reconstruction owner downstream of neural denoise and before demosaic without coupling that future owner into the denoiser graph.

## Safety / ownership result

- no second Vulkan instance/device/runtime owner;
- no device/manufacturer/sensor/lens identity conditioning;
- no classical denoise fallback;
- no CPU neural inference fallback;
- no full-frame RAW readback path;
- no production `IspCore` callsite integration in Phase 4;
- no highlight reconstruction in the denoise backend.

## Validation performed in this environment

- cumulative Python/reference tests;
- C++17 `-Wall -Wextra -Werror` foundation/model-package/execution-plan host tests;
- deterministic Vulkan package integrity and SHA verification;
- exact Phase-3 golden-vector package parity;
- exact full-frame vs tiled valid-center reference parity, including multi-tile seams and non-8-multiple dimensions;
- attachable CMake-module configure test;
- forbidden-symbol / CPU-fallback / phase-boundary source audits;
- exact ZIP-apply regression is performed at the Phase-4 release checkpoint.

## External device gate

The following is deliberately **not** claimed as executed here:

- NDK `glslc` compilation of the eight compute shaders;
- SPIR-V validation on the Android build toolchain;
- Vulkan driver execution of the frozen golden vectors;
- Android GPU latency/peak-memory measurement.

These are required on the target Android Vulkan device before a release model/backend is accepted. Absence of that hardware/toolchain does not introduce a CPU fallback and does not change Phase-4 source ownership.
