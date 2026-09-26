# Physical luma audit (2026-09-26)

Initial branch `fix/physical-luma-noise`, clean, HEAD `42c2cbb`;
protected chroma tag `baseline-physical-chroma-v1` at `4c98f4f`.

Actual pipeline: normalization / RAW fusion -> optional Spectra Student -> RAW
finalize (defects, green balance, lens shading, spatial exposure) -> final
demosaic (NORMAL/Malvar, QUALITY/AMaZE, BILINEAR/Neural JDD, AUTO/Auto Hybrid final
blend) -> physical chroma -> proposed physical luma -> AWB -> CCM / DNG HSM ->
LOG -> FLLF -> KHRONOS -> explicit profile detail / JPEG 4:4:4.

The common boundary is `IspCore.cpp`'s `executeSpectraResidentAwbCcm`, shader mode
3. Binding 1 is immutable final demosaic RGB; binding 2 is colour output. Chroma
preserves Y, so luma evidence can read binding 1 while its equal-RGB correction
is applied to the chroma result. No relocation or modification of chroma is needed.

`residualNoiseState.preDemosaic` starts from physical S/O and visible lens-shading
variance. A published Student result replaces it with its posterior, including
remaining LSC variance gain. Spatial exposure applies the existing mean squared
gain. `postDemosaic` propagates the selected reconstruction (including resolved
Auto Hybrid). Reuse its Y variance and the existing relative spatial sigma map;
do not derive strength from ISO or demosaic identity.

Fusion audit: `PhysicalNoiseCalibrationBridge.kt` had a residual scale field,
but it was missing from JPEG JNI and was discarded when the physical snapshot
was not yet available. Both omissions are fixed. The merger's historical
`spectraFusionVarianceScale` uses actual weights/correlations; the internal
`spectra.enabled` flag can mean physical temporal-model availability, not the
user's optional neural switch. Statistics now also run with that flag false,
including squared support weights and exposure-normalized physical variances.
No accumulation weights or RAW output values were changed. Luma applies this
scale to its propagated Y variance unless the Student posterior already owns
the residual. Chroma's protected covariance is not changed. A real merged RAW
with neural Spectra Off delivered variance scale 0.685 to luma after the fix.

Defects already belong to RAW-finalize/`RawDefectCorrectionPolicy`; luma must not
introduce another defect correction owner. Post-quantization sharpening is
retired. Explicit profile detail remains downstream; no tone/detail parameters
will be raised to compensate for smoothing. Covariance after the new adaptive
filter must remain labelled conservative unless a real posterior is estimated.

Reference choice: bounded two-scale directional lifting residuals, with
noise-normalized evidence from parallel stencils. Affine/DC components are fixed
points. Unlike an unconditionally attenuated high-pass, coherent residuals reduce
authority. Reference acceptance must precede GPU production integration.

Existing regression owners: PhysicalChromaDenoiseTest, PhysicalChromaDeviceTest,
SpectraNoisePropagationTest, SpectraResidualSeedConfidenceTest, physical-noise
Kotlin/source contracts. Prior broad suite has documented unrelated failures in
BASELINE_PHYSICAL_CHROMA.md; do not relabel those as passing.
