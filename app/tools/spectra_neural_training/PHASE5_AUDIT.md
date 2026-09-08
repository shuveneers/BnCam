# SPECTRA Neural Phase 5 — final audit

Baseline: GitHub `main` `34798c30d9364a4aecc0912e2c0110f2b9b34d25` + N006R + N007A-E + N008A-F + N009A-F + N010A-F + N011A-E.

## Scope result

Phase 5 connects the frozen Student-v1 Vulkan denoiser to the production single-frame RAW path. The neural stage is the sole general RAW noise-suppression pixel owner, is placed before demosaic and before remaining software lens shading, preserves exact fail/off bypass semantics, and keeps normal production execution GPU-resident. Developer-only scene-stage dumps are explicitly opt-in and their host readback cost is accounted separately from production telemetry.

## Masterprompt task closure

1. **Neural placed before demosaic and before remaining software LSC — COMPLETE (source/offline validated).** Production order is hard physical RAW correction -> neural Student -> remaining software LSC -> resident demosaic handoff.
2. **SPECTRA Core conditioning connected — COMPLETE.** Production wiring supplies canonical CFA geometry, S/O noise physics, metadata trust, black residual evidence, structured-noise evidence, remaining-LSC condition, exposure time, bit depth and capture domain. Unreliable analog/digital split metadata is not fabricated.
3. **Student residual + posterior connected — COMPLETE.** The production bridge publishes clean packed RAW, posterior variance, source saturation evidence and original headroom evidence from the frozen Vulkan backend.
4. **Exact Off bypass — COMPLETE.** Preflight runs before split hard-physical/neural stages. Disabled or zero-authority neural execution reruns the historical resident RawFinalize route from the immutable normalized RAW generation.
5. **OOD / safety gate — COMPLETE.** Invalid CFA/raw levels/noise/LSC/model/schema/runtime conditions fail closed to the exact baseline path. Student-v1 does not invent analog gain from ISO or post-RAW sensitivity boost.
6. **Fail = bypass — COMPLETE.** Neural dispatch, publication, transport or remaining-LSC failure discards split-stage output and reruns the historical resident RawFinalize path. No classical denoise fallback is introduced.
7. **Production demosaic modes remain compatible — COMPLETE at source-contract level; device execution gate open.** The post-neural remaining-LSC generation is accepted by the existing resident demosaic entry. Malvar, Neural JDD compatibility alias, AMaZE and Auto Hybrid routing remains unchanged downstream.
8. **RAW10 + RAW_SENSOR production semantics — COMPLETE at source-contract level.** Both capture domains feed the same canonical Bayer/conditioning path; no device-specific branch is introduced.
9. **Scene stage dumps — COMPLETE as developer-only observability.** Existing `rawJpegDebugDumpsEnabled` gates exact FP32 Bayer dumps for pre-neural hard-physical, post-neural and post-remaining-LSC stages. Debug readback failure never changes pixel authority or capture disposition.
10. **Latency / memory telemetry — COMPLETE.** Pre-physical, neural, remaining-LSC and total wall time, kernel dispatch counts, compact metadata upload, persistent GPU bytes, production full-frame CPU readback bytes, CPU fallback flag and separate debug readback/write overhead are exported to the existing RAW debug telemetry.
11. **No second denoise owner — COMPLETE.** No N006R-removed classical owner, CPU neural fallback, post-demosaic generic denoiser or automatic sharpening compensation is introduced by Phase 5.

## Stage-dump contract

Normal production:

```text
hard physical RAW
    -> neural Vulkan
    -> remaining LSC Vulkan
    -> demosaic resident
```

No full-frame host readback is requested by this path.

Developer dumps:

```text
01 pre_neural_hard_physical.rawf32
02 post_neural.rawf32
03 post_remaining_lsc.rawf32
```

Each dump contains one top-to-bottom FP32 Bayer mosaic. A JSON sidecar records dimensions, format, runtime stage count and readback telemetry. Debug readback is observability only and never grants, revokes or replaces neural pixel authority.

## Gain-conditioning correction

BnCam currently has no trustworthy per-frame analog/digital gain split in this path. `SENSOR_SENSITIVITY` is not silently reinterpreted as analog gain, and Camera2 post-RAW sensitivity boost is excluded because it belongs to the processed post-RAW path. The frozen Student-v1 export has `global_lowres_context=false` and exact-zero FiLM projection tensors, so compatibility gain slots are neutral 1.0 while `explicitGainMetadataValid=false` remains truthful telemetry.

## Safety / ownership result

- SPECTRA Core remains evidence/read-only.
- Neural denoise is the only general RAW noise-suppression owner.
- Remaining-LSC is physical lens correction only.
- Neural Off is exact baseline bypass.
- Model/OOD/runtime failure is exact baseline bypass.
- No CPU neural inference path exists.
- Normal production has no full-frame neural CPU readback.
- Developer stage readback is explicit, opt-in and separately measured.
- No device/manufacturer/sensor/lens identity conditioning was added.
- No N006R-removed classical denoise owner was restored.

## Validation performed in this environment

- full cumulative Python/reference/source-contract suite;
- Python `compileall`;
- C++17 `-Wall -Wextra -Werror` NeuralProductionPolicy host test;
- exact frozen Student-v1 package/reference parity retained;
- production ordering, bypass, RAW10/RAW_SENSOR and resident-demosaic source-contract audits;
- forbidden classical-owner / CPU-neural-fallback source audit;
- final delta ZIP content/hash audit.

## External device gate

The following is deliberately **not** claimed as executed here:

- full Android/NDK application build;
- NDK `glslc` compilation and SPIR-V validation for the new Phase-5 remaining-LSC shader;
- actual Android Vulkan execution of Student-v1 on RAW10/RAW_SENSOR captures;
- on-device execution of Malvar / Neural JDD / AMaZE / Auto Hybrid after neural publication;
- measured target-device latency and peak GPU memory.

Those remain target-device validation gates. They do not justify a CPU fallback or a second denoise owner and do not alter the Phase-5 source architecture.
