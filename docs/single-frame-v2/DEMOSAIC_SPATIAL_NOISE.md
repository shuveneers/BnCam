# BnCam SF2 — Phase 2: demosaic noise and spatial-correlation contract

**Status:** code added; native CI and Android/device validation are tracked separately.

## Objective and ownership

Phase 2 adds an analytic, **read-only** kernel-only prediction of how demosaicing correlates camera-linear luminance noise at neighboring pixel positions. It extends the immutable Phase-1 `SingleFrameNoiseContext`. The information is diagnostic only, with no denoiser, strength or GPU dataflow changes.

The already-accepted physical noise context owns effective capture S/O and propagated pre/post-demosaic residual covariance. The new correlation helper **never re-estimates S/O, multiplies an LSC or fusion gain, or overwrites an existing covariance**.

## Computation for fixed kernels

- For **Malvar-He-Cutler 2004** and the existing **bilinear reference**, reconstruct separate CFA-to-RGB impulse coefficients for all four RGGB phases.
- Project impulse coefficients to camera-linear Y with the existing `0.2126 R + 0.7152 G + 0.0722 B` weights.
- Compute spatial covariance of two output pixels from overlap at the **same underlying CFA input sites**; weight every site by its corresponding pre-demosaic R, G or B diagonal residual variance.
- Return phase-averaged horizontal `(1,0)`, vertical `(0,1)`, two diagonals `(1,1)` and `(1,-1)`, plus analytic kernel-only variance Y.
- The current post-demosaic noise prediction and confidence remain separate and unchanged, enabling diagnostics on predictor-to-analytic variance differences.

`kernelProxyAvailable=true` means the **fixed-kernel mathematical estimate was evaluated under an independent-CFA assumption**. It does **not** mean actual input pixel noise was measured independent, or that real demosaic output noise follows this complete correlation model. Both `inputIndependenceVerified` and `measured` remain false.

The method describes interior pixels and a locally stationary Bayer variance; borders, scene-dependent gradients, spatially variable RAW variance, demosaic nonlinearities and upstream correlated RAW noise are not included. No theoretical correlation is promoted into denoiser authority.

## Adaptive and correlated-input modes: explicit unknown

| Actual route / evidence | Status | Spatial rho |
|---|---|---|
| Fixed Malvar with valid diagonal pre-demosaic covariance | `FIXED_KERNEL_PROXY_INDEPENDENT_CFA_ASSUMED_NOT_MEASURED` | 4 theoretical kernel-overlap values |
| Bilinear reference with valid diagonal pre-demosaic covariance | Same proxy status | 4 theoretical values |
| AMaZE, BnC Neural, Menon or unsupported mode | `ADAPTIVE_OR_UNSUPPORTED_KERNEL_UNKNOWN` | **UNAVAILABLE** |
| Auto Hybrid (spatially varying blend) | `AUTO_HYBRID_SPATIAL_WEIGHTS_UNKNOWN` | **UNAVAILABLE** |
| Fixed reconstruction with published neural posterior | `NEURAL_POSTERIOR_INPUT_SPATIAL_CORRELATION_UNKNOWN` | **UNAVAILABLE** |
| Invalid/absent physical S/O, covariance or confidence | Corresponding invalid/absent status | **UNAVAILABLE** |

Unknown is never silently translated into rho=0. Neither empirical AMaZE spatial correlation nor the local Auto Hybrid blending weights are available as validated capture-local data at this stage. Spatial correlation measurement/estimation requires separate validation and likely the existing GPU-owned observations, not an additional unbounded full-frame readback.

## Code and tests

- `app/src/main/cpp/DemosaicNoiseCorrelation.h`: fixed-kernel overlap prediction with strict unsupported/invalid states.
- `app/src/main/cpp/SingleFrameNoiseContext.h`: immutable correlation field; diagnostic format prints rho fields **only** when a proxy is available.
- `app/src/test/cpp/DemosaicNoiseCorrelationTest.cpp`: independent impulse reconstruction for four offsets, Malvar/Bilinear, three unequal noise profiles, common scaling invariance, existing exact full-RGB covariance check, invalid and adaptive cases.
- `app/src/test/cpp/SingleFrameDemosaicContextTest.cpp`: context-level supported versus unknown reporting, including neural-posterior input.
- `app/src/test/cpp/Phase2ReadOnlySourceTest.py`: compare every `app/src/main/cpp` changed path with phase 1 and require **only two read-only headers changed/added**; `IspCore.cpp` exactly identical to phase 1.
- Phase-0 and phase-1 regression tests remain enabled.

**User testing:** no expected perceptual difference for this delta; verify baseline JPEG/same-RAW behavior and crash-free rendering. The next stage (RAW diagnostic estimation) can use the model as observational context, but cannot treat an unknown adaptive correlation as zero.

## Validation limits

Native CI is not an Android Gradle build. Phase-2 device compilation, performance on HONOR Magic 8 Pro, and photographic equivalence are pending. Absence of a new C++ pixel-writing path is supported by source-level regressions; it is not a proof of on-device equivalence.
