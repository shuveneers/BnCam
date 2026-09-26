# Baseline physical chroma: implementation and validation

2026-09-26. Branch `fix/adaptive-raw-baseline-color-chroma`, starting/final HEAD
`1ee1e70`. Changes are local and uncommitted; no branch change, reset, merge or
commit was performed. The initial tree was clean.

## Result and limits

A common physical chroma stage now runs after final demosaic RGB and before WB,
CCM, camera profile and tone. It runs with Spectra Off and On. The implementation
and its CPU/GPU detail tests pass. All four product demosaics published real JPEGs
from both RAW10 and RAW_SENSOR on the attached phone, with GPU execution confirmed.

This is **not an all-green repository test result**. The initial broad Kotlin run
had 357 failures among 1,937 tests. Re-running the failing classes against original
HEAD source files, in the same app-directory working directory, reproduced 356.
The remaining failure asserted that pre-WB RGB must remain completely unfiltered;
that temporary zero-denoise contract was deliberately replaced. The related old
physical-chroma source contract was also updated. The final targeted 25 tests pass.
Two older connected tests also fail on legacy expectations; see Tests below.

The physical scene was a dim cabinet/tiled wall with edges and shadow detail.
The original JPEG, subsequent captures and contact sheet were inspected. Residual
grain remains visible. Bright, saturated real objects, coloured wires and fabric
were not present; protection of those cases is supported by deterministic tests,
not a claim of exhaustive real-scene validation. ISO 200 was tested with a 250 ms
exposure in the same dark scene; this is not a bright-scene test.

## Audit and ownership

The missing stage was intentional: IspCore treated post-demosaic covariance as
read-only telemetry, and the resident colour shader passed `rawInput` directly
to WB. This left chroma noise intact with Spectra Off, and left any residual after
Spectra On without a common downstream chroma owner.

The current product routes are Malvar (`NORMAL`, bridge 1), AMaZE (`QUALITY`, 2),
Neural JDD (`BILINEAR` legacy symbol, 3) and Auto Hybrid (`AUTO`, 0). They all finish
in the resident demosaic output. Auto Hybrid is filtered once after its blend.
No component demosaic or its reconstruction math was changed.

`residualNoiseState.postDemosaic` is the production covariance owner. Its input
comes from capture-local physical S/O, lens shading and actual exposure gains.
When Spectra ran, the existing posterior propagation supplies its residual
covariance. Existing propagation handles the selected demosaic, including Auto
Hybrid. `cfaChromaConfidence`, `chromaAuthority` and `demosaicCfaEvidence` do not
enable the new filter. Physical model validity is its only availability gate.

The existing spatial sigma-ratio map is reused when marked as a relative physical
shape. It includes lens-shading variance propagation. If absent, the propagated
frame covariance is used without fabricating another sensor model. The scalar
spatial map and approximate reconstruction covariance remain approximations;
per-pixel cross-channel posterior covariance is not newly estimated here.

## Algorithm

`PhysicalChromaDenoise.h` is the CPU reference and failure-recovery implementation;
`physical_chroma_denoise.glsl` is its production GPU counterpart. Both use:

```
Y  = .2126 R + .7152 G + .0722 B
Cr = R - G; Cb = B - G
G' = Y - .2126 Cr' - .0722 Cb'
R' = G' + Cr'; B' = G' + Cb'
```

Y is never averaged, clipped, sharpened or otherwise corrected by this stage.
RGB reconstruction is not clipped, since clipping would invalidate Y identity.

Neighbour compatibility uses the sum of centre/neighbour covariance, full RG/BG
Mahalanobis distance including the off-diagonal, plus noise-normalized Y distance.
The correlation matrix is whitened and diagonalized before inversion. Adding
`1e-5` to its dimensionless diagonal regularizes rank-one cases without creating
a noise model. This formulation also resolved a CPU/GPU cancellation discrepancy
found during initial near-singular testing.

HF uses eight neighbours at radius one, with weight `exp(-distance/4)`. Mid/LF
uses radius three with `exp(-distance)`, and requires compatibility along the
intervening radius-one and radius-two path. Thus similar endpoints cannot simply
jump across a thin coloured or luminance boundary. Each estimate includes the
centre and uses normalized positive weights. The two correction contributions
are 75% HF and 25% LF, multiplied by physical chroma variance divided by
`variance + .01*Y^2`, a dimensionless SNR knee. There are no ISO/lens/sensor tables.
Constant coloured surfaces are fixed points; no neutral-colour target is imposed.
This is bounded local support, not a correction for arbitrary image-wide clouds.

Missing/invalid covariance is exact identity and reports
`PHYSICAL_CHROMA_BYPASS_NO_VALID_NOISE_MODEL`. Nonfinite neighbours do not contribute;
nonfinite centres/results bypass the estimator. Existing downstream finite guards
remain responsible for invalid source RGB. CPU recovery is only entered after
typed GPU failure, and requires the exact finalized RAW generation if host RGB
is absent. There is no normal CPU image scan or roundtrip.

## Vulkan and telemetry

The stage is fused immediately before WB in the existing resident colour dispatch.
Binding 1 remains immutable demosaic RGB; binding 2 receives the colour output.
The 128-byte push contract is retained. Retired cloud-map storage now transports
the existing spatial sigma ratios; the old cloud correction remains disabled.
No new full-frame buffer, dispatch, or full-frame readback is introduced.

Workgroup reductions measure max/mean/RMS Y error, effective HF/LF authority,
affected fraction and before/after full-frame colour-field variance. These field
variances include scene colour and **are not labelled measured sensor-noise
variance**. Physical sigmas/covariance are explicitly predicted. Edge and HF
energy metrics are explicitly test-only, not fabricated capture telemetry.
CPU-failure telemetry marks GPU measurements unavailable.

Downstream noise propagation conservatively retains pre-filter covariance and
labels it accordingly; it does not claim a measured adaptive-filter posterior.
AWB behaviour, LOG -> FLLF -> KHRONOS, JPEG 4:4:4, DNG, RAW masters, warm buffers,
YUV, preview and Spectra defaults were not changed.

## Numerical evidence

The float32 maximum absolute Y tolerance is `8*FLT_EPSILON*max(1,magnitude)`:
`9.536743164e-7` for unit-range frames. Mean and RMS cannot exceed that enforced
maximum. Edge/HF ratios must be within `2e-5` of one. Any violation rejects a
chroma improvement; explicit negative tests exercise all three rejection gates.

CPU and actual production GPU each process 20 deterministic cases: neutral/dark
noise, high SNR, high/low luma edges, red/green and blue/yellow boundaries,
equal-Y colour boundary, noisy checkerboard, one/two-pixel lines, textile and text
strokes, fine chromatic texture, correlated/strong off-diagonal/rank-one noise,
missing and nonfinite models, a coherent mid-frequency cloud and constant colour.

Final GPU results:

| Metric | Result |
|---|---:|
| Worst max Y error | 1.788139343e-7 |
| Worst mean absolute Y error | 1.865555532e-8 |
| Worst RMS Y error | 3.984058208e-8 |
| Edge amplitude ratio range | 0.9999994041 to 1.0 |
| HF Y-energy ratio range | 0.9999998836 to 1.000000023 |
| CPU/GPU maximum RGB difference | 1.937151e-7 |
| Neutral noise chroma variance ratio | 0.35794 |
| Dark read-noise chroma variance ratio | 0.27148 |
| Correlated noise ratio | 0.26867 |
| Strong off-diagonal noise ratio | 0.27105 |
| Near-singular noise ratio | 0.22972 |
| Synthetic mid-frequency cloud ratio | 0.41900 |

For numerically zero-energy flat Y fields, a relative HF ratio is undefined and
reported as the identity convention (`hfRatioDefined=0`); absolute Y errors are
still checked. Meaningful edge/HF claims use the structured patterns. Full
before/after energies and all per-case values are in
[native-gpu-validation.txt](physical-chroma/native-gpu-validation.txt).

## Real captures

All rows below published successfully, with resident input, no CPU RGB upload,
deferred full-frame readback, and `gpuExecuted=true`. All high-ISO captures had
maximum Y error `5.96046e-8`, mean error at most `4.38945e-9`, and RMS at most
`7.82237e-9`. The ratio below measures colour-field variance, not isolated noise.

| Format | Demosaic | Spectra | ISO | Field variance ratio |
|---|---|---|---:|---:|
| RAW_SENSOR | Malvar | Off | 39000 | .9149 |
| RAW_SENSOR | AMaZE | Off | 39000 | .8862 |
| RAW_SENSOR | Neural JDD | Off | 39000 | .8687 |
| RAW_SENSOR | Auto Hybrid | Off | 39000 | .8980 |
| RAW10 | Malvar | Off | 38100 | .9180 |
| RAW10 | AMaZE | Off | 38100 | .8907 |
| RAW10 | Neural JDD | Off | 38100 | .8680 |
| RAW10 | Auto Hybrid | Off | 38100 | .8999 |
| RAW_SENSOR | Malvar | On | 38100 | .9227 |
| RAW10 | Malvar | On | 37200 | .9250 |
| RAW_SENSOR | Malvar | Off | 200 | .8663 |

The ISO-200 maximum/mean/RMS Y errors were `1.86265e-9`, `9.54867e-11`,
`1.71427e-10`. Full compact evidence is in
[device-results.json](physical-chroma/device-results.json).

Spectra On did not amplify the observed pre-baseline post-demosaic residual in
this scene: RAW_SENSOR RG/BG robust variances changed from about
`6.10714e-5 / 1.62695e-5` (Off) to `4.88704e-5 / 1.20827e-5` (On).
These are separate captures, not identical-RAW A/B evidence. No general claim
about the user's reported Spectra amplification is established, and Spectra was
not redesigned. Off was tested first and restored after compatibility testing.

## Performance, build and tests

NDK `28.2.13676358`, arm64-v8a, target API 36. The device reports Android 17;
the prompt's Android 15 description is not the installed device OS. Debug APK
and test APK build successfully, install, and launch `com.bncam/.MainActivity`.

Paired warm timestamp tests on the same 1024-square frame measured approximately
2.9–3.3 ms additional GPU time. In the 3072x4080 capture, colour kernel time was
22.70 ms before versus 76.13 ms with chroma (about +53.43 ms, including expanded
diagnostics). WB/colour wall time was 59.62 versus 118.36 ms. Total shot times
were 1825 versus 1809 ms in the two captures; that uncontrolled pair cannot prove
zero total latency impact. The expected added work is the measured colour-stage
cost, not the apparent improvement in total shot time.

There are zero extra full-frame buffers/readbacks. Persistent resident memory
increased by 2,358,912 bytes: 2,350,080 bytes of additional per-workgroup statistics
plus compact spatial-map capacity. Existing allocations are reused after growth.
The additional statistics are compact CPU readback, not a full RGB readback.

Passed:

- 25 targeted Kotlin tests covering physical noise, baseline ownership and retired
  filter exclusion.
- New connected physical-chroma CPU/GPU test, including all four resident demosaic
  routes, CPU/GPU parity and timestamp benchmark.
- Standalone arm64 native `PhysicalChromaDenoiseTest`, `SpectraNoisePropagationTest`,
  `SpectraResidualSeedConfidenceTest`, `PhysicalNoiseNativeContractTest`.
- Eleven real RAW captures listed above; JPEG publication and actual images checked.

Not green:

- Broad initial Kotlin suite: 357/1937 failures. 356 reproduced against original
  sources; one superseded zero-denoise expectation was updated. This task does
  not repair unrelated historical tests or their working-directory assumptions.
- `DemosaicNativeValidationTest`: native bilinear/Malvar/Neural/AMaZE/Menon math
  checks report passed, but retired product-slot expectations fail.
- `NoiseModelNativeConnectedTest`: S/O receipt and variance response pass;
  its existing provenance/retired-NR-owner acceptance fails. The pre-existing
  validator functions and their production dependencies were not changed.

Reproduce the new device validation after building/installing both debug APKs:

```
adb shell am instrument -w -e class com.bncam.PhysicalChromaDeviceTest com.bncam.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as com.bncam cat files/physical_chroma_validation.txt
```

The debug benchmark receiver now accepts `--ez spectra true/false`, allowing
repeatable Off/On validation without changing production defaults or adding UI.

## Changed files

- `PhysicalChromaDenoise.h`: reference/filter contract and numerical acceptance.
- `IspCore.cpp`: common covariance handoff, typed CPU recovery, telemetry.
- `VulkanSpectraResidentDemosaicBackend.h/.cpp`: parameter transport, reused
  spatial-map storage and compact reductions.
- `physical_chroma_denoise.glsl`, `spectra_demosaic_resident.comp`, `CMakeLists.txt`:
  production estimator, fused invocation and shader dependency.
- `tests/PhysicalChromaValidation.h`, native test, JNI/ImageUtils entry and
  `PhysicalChromaDeviceTest.kt`: deterministic CPU/GPU validation and benchmark.
- Two physical/zero-denoise Kotlin source contracts: updated intended ownership.
- Debug benchmark receiver: Spectra test control.
- This report and its compact evidence files.

Remaining validation: a bright real scene and saturated fine-colour subjects,
broader devices/lenses, and controlled repeated total-latency measurements. The
captured dim-scene result and deterministic detail contract are verified; full
completion of every original acceptance checkbox is not claimed.

Session artefacts (captures, raw logs and original-source regression snapshot) are
retained in `work/physical_chroma_validation/`. The user chose to complete with the
current scene; no further bright-scene setup is pending. The original RAW_SENSOR,
AMaZE and Spectra Off selection was restored; manual test exposure was cleared.

A final capture on the installed final APK also published successfully and
reported `measurementStatus=GPU_MEASURED`, max Y error `2.38419e-7`, mean
`1.77188e-9`, RMS `5.77373e-9`. See
[final-installed-capture.txt](physical-chroma/final-installed-capture.txt).
JPEG headers from the eight matrix image files were independently checked: all
report subsampling code 0 (4:4:4), 3072x4080 pixels.
