# BnCam SF2 — Phase 3: Poisson–Gaussian RAW diagnostic

## Scope and use

A single RAW frame can provide **limited empirical evidence** about the relation between local signal and residual sample variance, `Var(x) ≈ S*x + O`. It does **not** identify sensor shot/read noise uniquely, because scene detail, structure, preprocessing and clipping contaminate local sample statistics.

SF2-D03 supplies an **optional, existing-CPU-RAW-only, debug diagnostic**. It does not create a second `S/O` owner, alter RAW pixels, modify chroma, affect luma stages, create CPU copies, or introduce resident Vulkan readbacks. It is not a calibrated noise-profile estimator and must not be used for runtime noise authority or filter optimization.

## Runtime control

The estimator is called only when all conditions hold:

1. Existing `workingRaw.mosaic` is a valid normalized `CV_32FC1` CPU input.
2. The capture is explicitly `singleShotRaw`.
3. Existing `rawJpegDebugDumpsEnabled` is true.
4. The render is **not** on the resident GPU input route.

For the normal GPU-resident route it reports `GPU_RESIDENT_NO_READBACK`. If debug is disabled it reports `DEBUG_DISABLED`. Multi-frame requests report `SINGLE_EXPOSURE_REQUIRED`. It never materializes CPU RAW. The source observation is made before lens shading, RAW finalization and demosaic, using existing physical `effectiveS[4]` and `effectiveO[4]`.

An `sf2RawPoissonGaussian` segment is appended to the current render debug text. Fit coefficients are emitted only for a fully accepted four-channel result; otherwise `diagnosticCoefficients=UNAVAILABLE`.

## Estimation method (non-calibrating)

- The existing canonical CFA mapping, `RawCfaLevelMapping.h`, separates R/Gr/Gb/B correctly across RGGB/GRBG/GBRG/BGGR.
- From existing normalized RAW, sample one 16×16 block per 32×32 input area (bounded sub-sampling, not an unconditional full-frame scan).
- Each tile provides 64 Bayer-phase samples per canonical channel. Compute Welford mean and sample variance.
- Reject nonfinite, clipped or near-black pixels and samples with excessive texture or variance disagreement with existing physical `effectiveS/O`. This **introduces prior selection bias** and therefore the fitted `S/O` is *not independent ground truth*.
- Fit a linear variance–signal relationship for each channel only when at least 24 accepted tiles span ≥0.25 normalized signal, with a positive identifiable slope and intercept.
- Require agreement within a broad band of the existing physical values and minimum heuristic fit support. These limits suppress obvious instability but do not establish calibration accuracy.
- `supportConfidence` is a numeric diagnostic/support score, **not a validated posterior probability**, and is not fed to production.

This estimator is intentionally conservative and may report `INSUFFICIENT_OR_CONFLICTING_EVIDENCE_PHYSICAL_SO_UNCHANGED` on legitimate photographs. That is preferable to silently treating texture or demosaic correlation as sensor noise.

## No duplicate noise authority

Only `FinalSensorCalibrationNative.effectiveS/effectiveO` remain physical noise authority. The diagnostic:

- never alters `workingMeta.calibration` or the calibration snapshot;
- never scales by ISO, WB, CCM, LSC or fusion;
- never writes to `IspCore` RAW pixels, `SingleFrameNoiseContext`, a GPU request or a denoise strength;
- never substitutes a fitted value when physical S/O is absent;
- leaves the existing camera JPEG processing path byte-identical after stripping exactly the new debug hook and format line.

The implementation is **not** wired to the normal Vulkan image pipeline. No speed or photographic improvement is claimed in this phase.

## Tests and known gaps

Native `RawPoissonGaussianEstimatorTest` uses synthetic heteroscedastic Gaussian CFA fields on all four Bayer patterns, verifies fit tolerances, invalid/absent physical S/O, clipping, uniform data and structured textures, and explicitly checks that no model replacement flag can become true. `Phase3ReadOnlySourceTest` checks phase-2-to-phase-3 pixel-source identity, while the phase-0/1/2 tests continue to run.

No actual RAW dataset or sensor-truth calibration has been used here. Results from the HONOR Magic 8 Pro require separate offline reference data before accepting any measured noise slope or offset. No Android Gradle build, APK install, Vulkan run or phone benchmark is available in this environment.

## Release boundary

Phase 3 is based on cumulative phase-2 commit `9fba3da109825977630b2cf6cfa031c16dcba31f`. It does not import the unfinished, unrelated `feature/single-frame-engine-v2` branch. Next closed phase: **SF2-D04 Guided Luma Fine**, subject to a separate work interval and validation.
