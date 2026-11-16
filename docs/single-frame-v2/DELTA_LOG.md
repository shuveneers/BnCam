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

## Exact release boundary

- Phase-1 base SHA: `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`.
- Phase-2 release branch: `deliverables/sf2-phase2`.
- Full project downloadable via the GitHub branch ZIP.
- Changes-only ZIP downloadable as the `BnCam_SF2_F2_Delta` GitHub Actions artifact; its ZIP includes changed files and a reproducible `.patch` against phase 1.
- The unrelated `feature/single-frame-engine-v2` experiment is not included.
- **Next closed phase: SF2-D03 RAW Poisson–Gaussian diagnostics, read-only.** Not started on this release branch.
