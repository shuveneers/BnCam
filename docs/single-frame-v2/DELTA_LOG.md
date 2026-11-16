# SF2 delta log — incremental delivery chain

| ID | Status | Implementation | Evidence / limitations |
|---|---|---|---|
| SF2-D00-01 | CODE COMPLETE | Main SHA 2778264 pinned; separate release chain | Baseline |
| SF2-D00-02/03/04 | CODE COMPLETE / PHONE PENDING | Chroma-only default, debug-only legacy luma, same-RAW A/B; frozen source and pixel contracts | Phase-0 native CI success 43834301957; no APK |
| SF2-D01-01 | CODE COMPLETE / PHONE PENDING | Immutable `SingleFrameNoiseContext`, existing physical S/O and propagated residual states | Native tests; no second S/O resolver |
| SF2-D01-02 | CODE COMPLETE / PHONE PENDING | `SingleFrameNoiseContextTest` and domain contract | One-time LSC/spatial gain and RAW sensor domain tested |
| SF2-D01-03 | CODE COMPLETE / PHONE PENDING | `IspCore.cpp` diagnostic-only object construction; native source identity | Phase-1 11/11 native CI 43836979969 |
| SF2-D02-01 | CODE COMPLETE / PHONE PENDING | `DemosaicNoiseCorrelation.h`: R/Gr/Gb/B fixed kernel impulse overlap, phase-averaged camera-linear Y correlations for Malvar/Bilinear at four offsets; explicit unknown AMaZE/Auto Hybrid/neural posterior | `DemosaicNoiseCorrelationTest` independently reconstructs impulse kernels and cross-checks existing fixed-RGB covariance; no spatial independence measured |
| SF2-D02-02 | CODE COMPLETE / PHONE PENDING | Correlation attached to immutable `SingleFrameNoiseContext` with source provenance; diagnostics only show rho if proxy available | `SingleFrameDemosaicContextTest` verifies unknown values never published as rho=0 |
| SF2-D02-03 | CODE COMPLETE / PHONE PENDING | `Phase2ReadOnlySourceTest.py` and phase-2 native workflow with changed-files artifact ZIP | CI **14/14 passed** at 43838446474, including phase-0/1 regression and source identity; no APK/device test |

| SF2-D03-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `RawPoissonGaussianEstimator.h`: optional read-only normalized pre-LSC Bayer variance–signal fit; existing effective S/O-guided tile rejection; no calibration authority | Synthetic CFA native tests; physical S/O unchanged |
| SF2-D03-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` reads existing CPU RAW only when debug enabled and single-frame; normal resident GPU skips without readback | Phase-0/1/2 source regression preserved; no APK |
| SF2-D03-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | C++ tests, phase-3 source contract and GitHub ZIP packaging | 16/16 native CI succeeded at run 43840240102; no real RAW/phone calibration |

| SF2-D04-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `SingleFrameGuidedLuma.h` full-res bounded 3×3 guided fine CPU oracle; additive scalar RGB residual, local physical noise, coherent-line gate, strict bypass | Synthetic native flat noise/detail/chroma tests passed; device pending |
| SF2-D04-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `physical_luma_denoise.glsl` Guided Fine; legacy GLSL body preserved exactly | Actual Vulkan `spectra_demosaic_resident.comp` SPIR-V compile in GitHub CI; GPU pixel parity pending |
| SF2-D04-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | Vulkan color transform request carries explicit OFF/legacy/guided stage via existing mode-3 push bits; no ABI/readback changes | Native source isolation plus GLSL compile; no device GPU run |
| SF2-D04-04 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` routes explicit debug Guided Fine through resident Vulkan and CPU fallback, retaining OFF as normal default and legacy reference | Frozen OFF/legacy ISP source identity test passed; no full Android build |
| SF2-D04-05 | CODE COMPLETE / DEVICE VALIDATION PENDING | Debug JNI/Kotlin stage control and same-RAW three-JPEG quality comparison (`off.jpg`, `on.jpg`, `guided.jpg`) | Source contracts check explicit stage and guaranteed reset; photo assessment pending |
| SF2-D04-06 | CODE COMPLETE / DEVICE VALIDATION PENDING | Phase-4 synthetic test, protected-source tests, native and GLSL CI, changes-only ZIP | **18/18 native tests and SPIR-V compile passed** in run 43844425567; no APK/phone |

## Exact release boundary

- Phase-1 base SHA: `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`.
- Phase-2 release branch: `deliverables/sf2-phase2`.
- Phase-3 release branch: `deliverables/sf2-phase3` based on phase-2 SHA `9fba3da109825977630b2cf6cfa031c16dcba31f`.
- Full project downloadable via the GitHub branch ZIP.
- Changes-only ZIP downloadable as the `BnCam_SF2_F2_Delta` GitHub Actions artifact; its ZIP includes changed files and a reproducible `.patch` against phase 1.
- The unrelated `feature/single-frame-engine-v2` experiment is not included.
- Phase-4 branch: `deliverables/sf2-phase4`; phase-3 base SHA: `76e98fbf09ba6905323b0356293118c8f6892381`.
- **Next closed phase: SF2-D05 Guided Luma Coarse.** Not started on phase 4.
