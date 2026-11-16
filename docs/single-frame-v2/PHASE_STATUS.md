# SF2 phase statuses — delivery chain

**Delivery rule:** each `deliverables/sf2-phaseN` branch is a cumulative full project; each CI artifact ZIP contains ONLY that phase's changed files and a patch against the preceding phase. Original `main` is not modified.

| Phase | Status | Notes |
|---|---|---|
| 0 chroma-only | DEVICE VALIDATION PENDING | Code and native CI complete (run 43834301957); HONOR/APK pending |
| 1 physical noise foundation | DEVICE VALIDATION PENDING | Immutable context, physical noise provenance; native CI 11/11 (run 43836979969); HONOR/APK pending |
| 2 demosaic noise | CODE COMPLETE / DEVICE VALIDATION PENDING | Malvar/Bilinear phase-averaged fixed-kernel correlation-only proxy; explicit unknown AMaZE/Auto Hybrid/neural-posterior; native CI **14/14 passed** (run 43838446474); HONOR/APK pending |
| 3 RAW Poisson-Gaussian diagnostics | CODE COMPLETE / DEVICE VALIDATION PENDING | Optional CPU-RAW debug-only S/O-informed fit; no Vulkan readback, no S/O override; 16/16 native CI passed (43840240102); Android/phone tests pending |
| 4 guided fine | CODE COMPLETE / DEVICE VALIDATION PENDING | CPU reference + debug-gated resident Vulkan shader, same-RAW OFF/legacy/guided comparison; native 18/18 and SPIR-V shader CI passed (43844425567); no Android APK/device validation |
| 5 guided coarse | NOT STARTED | Unimplemented |
| 6 confidence gate | NOT STARTED IN DELIVERY CHAIN | Separate experimental work failed latest earlier CI; not promoted |
| 7 integration | NOT STARTED | Requires phase sequencing and device tests |

Phase 2 is based on **phase-1 HEAD** `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`. Only `DemosaicNoiseCorrelation.h` and `SingleFrameNoiseContext.h` differ under `app/src/main/cpp`.

### Limits

- `kernelProxyAvailable` is **not** a verified total post-demosaic spatial correlation. Input CFA spatial independence is an assumption, not a measured result. Pixel changes: none introduced by phase 2.
- No Android Gradle build, APK, Vulkan runtime benchmark, or HONOR Magic 8 Pro photographic validation was performed here.
- If phase 2 is installed as a complete project, it includes phases 0 and 1. Phase 2 does **not** include the unfinished `feature/single-frame-engine-v2` branch.

## Phase 3 release

Branch `deliverables/sf2-phase3`, based exactly on phase-2 SHA `9fba3da109825977630b2cf6cfa031c16dcba31f`. Read-only `RawPoissonGaussianEstimator` is used only when host RAW exists, the capture is single-frame and the developer dump flag is enabled. No mandatory RAW CPU path was added. The normal GPU-resident route reports `GPU_RESIDENT_NO_READBACK` and does not run the estimator. A numeric debug fit does not qualify as a replacement for Camera2/effective physical S/O. No Android APK or device validation was performed.

## Phase 4 release

- Based on `deliverables/sf2-phase3` at `76e98fbf09ba6905323b0356293118c8f6892381`.
- Branch: `deliverables/sf2-phase4`, cumulative full project. `main` unchanged.
- Normal product luma defaults to OFF. `GUIDED_V2` is available only via an opt-in debug JNI selector; the previous legacy stage remains separately available.
- CPU and GLSL Guided Fine are implemented; 18 native regression tests and SPIR-V compilation passed. The test assertions verify a synthetic flat-field noise reduction and preservation of step/thin-line patterns, but **do not** constitute real-world photographic validation or an on-device CPU/GPU parity result.
- Same immutable master RAW debug renders: `off.jpg`, `on.jpg`, `guided.jpg`, each with diagnostic text.
- Android Gradle/NDK APK build, GPU runtime, performance, CPU/GPU numerical parity and HONOR Magic 8 Pro RAW photographic validation remain pending.
