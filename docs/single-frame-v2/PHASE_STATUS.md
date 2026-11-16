# SF2 phase statuses — delivery chain

**Delivery rule:** each `deliverables/sf2-phaseN` branch is a cumulative full project; each CI artifact ZIP contains ONLY that phase's changed files and a patch against the preceding phase. Original `main` is not modified.

| Phase | Status | Notes |
|---|---|---|
| 0 chroma-only | DEVICE VALIDATION PENDING | Code and native CI complete (run 43834301957); HONOR/APK pending |
| 1 physical noise foundation | DEVICE VALIDATION PENDING | Immutable context, physical noise provenance; native CI 11/11 (run 43836979969); HONOR/APK pending |
| 2 demosaic noise | CODE COMPLETE / DEVICE VALIDATION PENDING | Malvar/Bilinear phase-averaged fixed-kernel correlation-only proxy; explicit unknown AMaZE/Auto Hybrid/neural-posterior; native CI **14/14 passed** (run 43838446474); HONOR/APK pending |
| 3 RAW estimator | NOT STARTED IN DELIVERY CHAIN | Separate experimental feature-branch code exists, not phase-3 deliverable |
| 4 guided fine | NOT STARTED IN DELIVERY CHAIN | Separate experimental feature-branch code exists, not released |
| 5 guided coarse | NOT STARTED | Unimplemented |
| 6 confidence gate | NOT STARTED IN DELIVERY CHAIN | Separate experimental work failed latest earlier CI; not promoted |
| 7 integration | NOT STARTED | Requires phase sequencing and device tests |

Phase 2 is based on **phase-1 HEAD** `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`. Only `DemosaicNoiseCorrelation.h` and `SingleFrameNoiseContext.h` differ under `app/src/main/cpp`.

### Limits

- `kernelProxyAvailable` is **not** a verified total post-demosaic spatial correlation. Input CFA spatial independence is an assumption, not a measured result. Pixel changes: none introduced by phase 2.
- No Android Gradle build, APK, Vulkan runtime benchmark, or HONOR Magic 8 Pro photographic validation was performed here.
- If phase 2 is installed as a complete project, it includes phases 0 and 1. Phase 2 does **not** include the unfinished `feature/single-frame-engine-v2` branch.
