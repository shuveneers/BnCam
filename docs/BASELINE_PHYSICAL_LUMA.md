# Physical baseline luma: implementation and measured validation

2026-09-26. Local changes on `fix/physical-luma-noise`, initial HEAD `42c2cbb`.
No checkout, reset, rebase, merge or commit. Main remains
`42c2cbb39ce0b8582873dbf913338f7cc9aaea32`; the protected annotated tag still
resolves to `4c98f4f95139e2969455afd83917bcd7e0adc66d`.

## Result and scope

**Photographic acceptance is NOT complete.** The user reported a RAW viewfinder
freeze/crash and unacceptable noise on the main and ultrawide after installation.
Passing synthetic tests and same-RAW A/B checks below does not override that report.
The noise regression relative to the earlier installed version is not yet explained.

An automatic, separate Vulkan physical luma owner now follows physical chroma
and precedes AWB in the common final-demosaic colour dispatch. It is enabled by
valid physical Y variance, independent of Spectra and demosaic selection.
No normal UI slider was added. The original chroma reference and shader are
unchanged. LOG -> FLLF -> KHRONOS, AWB, CCM/HSM, sharpening, JPEG 4:4:4,
YUV is unchanged. A subsequent viewfinder recovery fix is described below. Merger changes affect
variance statistics only; RAW accumulation and DNG payload values are unchanged.
Debug A/B captures verified the immutable master CRC after both developments.

Native ground-truth tests, production GPU parity, all four product demosaics,
RAW_SENSOR and RAW10 captures pass the checks described below. Real same-RAW
A/B images retain visible grain. This is not a claim that all photographic
detail is preserved under every condition. Hair, fine printed text, an in-focus
fabric chart and broad bright-scene/device coverage were not established by
the available real scene; those structures have synthetic coverage where listed.

## Missing architecture and ownership

The existing baseline owned opponent chroma only. Its output went directly to
WB, leaving no independent baseline owner for random Y residuals. The new call
is exactly once in shader mode 3, after `physicalChromaDenoise` and before WB.
Malvar, AMaZE, Neural JDD (legacy `BILINEAR` product symbol) and Auto Hybrid all
arrive here; Auto Hybrid is processed after its final blend.

Chroma preserves Y, so luma reads immutable demosaic Y as its evidence and
applies its result to post-chroma RGB. This avoids another image buffer and
dispatch. Typed Vulkan failure recovery uses the CPU reference on an immutable
copy. There is no normal CPU image scan or GPU/CPU/GPU roundtrip.

See [the audit](PHYSICAL_LUMA_AUDIT.md) for the original route and covariance
owners. The actual input is `residualNoiseState.postDemosaic.varianceY`, its
confidence, and the existing relative physical spatial sigma map. S/O, LSC and
exposure gains are propagated upstream. The Student posterior replaces the
pre-demosaic covariance when published; it is not scaled by fusion a second time.

Two fusion transport omissions were corrected: the Kotlin bridge previously
discarded statistics if shutter S/O resolution was pending, and the JPEG JNI
did not carry the residual scale. Actual squared fusion weights, source variance
ratios and available temporal correlation supply the scale, never requested N.
The luma model alone receives this correction, preserving chroma behaviour.
The final real multiframe capture reports `fusionVarianceScale=0.685` with
Spectra Off. This is a global residual prediction, not a new per-pixel posterior.

## Mathematical model

Use `Y=.2126R+.7152G+.0722B`. For four directions `(1,0),(0,1),(1,1),(1,-1)`
and radii r=1,2, form redundant lifting residuals:

```
d_r(x) = Y(x) - (Y(x-r*d) + Y(x+r*d))/2
v_r(x) = v(x) + (v(x-r*d) + v(x+r*d))/4
v(x)   = propagatedVarianceY * spatialSigmaRatio(x)^2
```

There is no decimation or filtered coarse image. Radius one responds most to
fine fluctuations; radius two has a different frequency response (zero at
axial Nyquist, maximum at half Nyquist), providing mid-frequency evidence.
Affine illumination is a fixed point away from clamped boundaries. No coarse
DC/low-frequency band is smoothed, and no banding/defect model is invented.

Five parallel residuals per direction provide mean m, observed power P and
predicted noise V. Their stencils are disjoint under the white-noise reference:

```
coherentPower = max(m*m - V/5, 0)
structure    = coherentPower / (coherentPower + V/5)
signalPower  = max(P - V, 0)
noiseLikelihood = V / (V + 4*signalPower)
physicalAuthority = confidence*v / (v + .001*Y*Y)
```

The maximum directional structure confidence protects each scale. The
subtracted correction averages its directional residuals with weights
`physicalAuthority * (1-structure) * noiseLikelihood`, capped at .28 for the
fine scale and .16 for the mid scale. These are universal conservative caps,
not sensor/ISO/demosaic strength tables. A weak line can accumulate directional
evidence over five samples even when its individual residual overlaps noise.
Local power provides additional protection for alternating/repeated texture.

The total attenuation is bounded by .44; remaining grain is retained directly,
never synthesized. There is no sharpening, microcontrast boost, median,
unconditional blur or generic RGB filtering. The decomposition reconstructs by
subtracting only the estimated noise-like residual. Uncertainty reduces authority.

Marginal post-demosaic covariance does not provide all spatial noise correlations.
The parallel-stencil noise prediction is therefore an approximation for real
demosaic/Spectra outputs, explicitly not an exact spatial posterior. The current
global covariance/spatial map also approximates local signal-dependent variance.
Downstream covariance remains a labelled conservative pre-filter prediction.
Existing tone derivatives amplify both signal and noise; no separate obvious
detail-amplification defect was established, and tone/detail was not redesigned.

## Chroma and invalid-state guarantees

Subtract the identical scalar correction from R, G and B. Algebraically R-G
and B-G remain unchanged; no clipping occurs in this stage. Constant coloured
patches are fixed points, including their hue/saturation. This does not assert
that nonlinear HSV saturation of every noisy pixel stays exactly constant when
Y changes; the enforced invariants are opponent differences and clean patches.

Missing, negative or nonfinite physical variance/confidence returns exact input.
Missing model reports `PHYSICAL_LUMA_BYPASS_NO_VALID_NOISE_MODEL`. Nonfinite local
evidence also fails to identity. Defects remain owned by RAW-finalize; the
isolated bright-excursion test protects a potential point highlight.

## Synthetic ground-truth results

21 cases x 8 deterministic noise seeds pass on native arm64. Clean sources are
corrupted by known shot/read variance `S*max(Y,0)+O`, then compared to ground
truth. Cases include dark/midtone/bright flats, strong/weak edges, one/two-pixel
lines, text strokes, checkerboard, weave, wood, multiscale foliage, weak repeating
and sub-sigma structure, random noise, point excursions, missing/near-zero model,
single/reduced covariance, and clean colour. Additional invalid-model checks pass.

| Metric over eight seeds | Measured result |
|---|---:|
| Dark flat output/input noise MSE (variance for this flat source) | .7570–.7693 |
| Midtone flat output/input noise MSE | .8375–.8461 |
| Strong edge maximum amplitude change relative to noisy input | .00976% |
| Strong edge maximum position change | .000190 px |
| Strong edge maximum spread increase | .001287 px |
| Weak edge maximum amplitude change | .522% |
| Weak edge maximum position change | .0800 px |
| Weak edge maximum spread increase | .03977 px |
| One/two-pixel line amplitude retention vs input | 99.732–99.949% |
| Weave ground-truth projection after | .99496–1.00629 |
| Wood ground-truth projection after | .98205–1.00917 |
| Foliage ground-truth projection after | .99276–1.00444 |
| Weak repeating ground-truth projection after | .95507–.98757 |
| Sub-sigma coherent ground-truth projection after | .91916–.99898 |
| High-SNR and near-zero-noise maximum Y change | 0 at float32 precision |

The sub-sigma numbers include sampling error already present in the noisy source;
they are not a claim of zero bias. Tests constrain worsening of ground-truth
projection error (5 percentage points, 10 for the sub-sigma case), correlation,
orientation, RMSE, edge geometry and line width. Flat noise must improve by at
least 15% while retaining at least 25% of input error energy. Edge contrast has
a 3% limit, position/spread .15 px, and line contrast a 5% limit. Violating detail
or chroma limits fails acceptance regardless of noise improvement. All structured
cases improved correlation to the clean source in this run.

The reduced-covariance test holds input fixed and reduces predicted variance to
prove authority decreases; it is a sensitivity test, not simulated N-frame fusion.
Real weighted fusion is separately checked on device.

Full measurements: [eight seeds](physical-luma/synthetic-eight-seeds.txt) and
[ranges](physical-luma/synthetic-summary.json).

## GPU, build and regression tests

CPU and actual production Vulkan each pass the 21 cases. Maximum RGB difference
in the base suite is `5.960464e-8`. A joint chroma/luma test with a heterogeneous
2x2 sigma map also passes the `2e-6` parity bound. All four resident demosaic
routes execute the common luma owner successfully. Existing physical-chroma
GPU tests pass without weakening their Y/detail tolerances.

Passed native executables: PhysicalLumaDenoiseTest, PhysicalChromaDenoiseTest,
SpectraNoisePropagationTest, SpectraResidualSeedConfidenceTest,
PhysicalNoiseNativeContractTest and SpectraTemporalFusionTest.

18 targeted Kotlin tests pass: luma ownership, chroma ownership, physical JNI,
capture authority, and fusion calibration. The fusion fixture now explicitly
declares frozen OEM authority rather than relying on ambient registry state.
An early wider selection also hit two existing failures: the retired-adaptive-JNI
expectation in Phase6PhysicalNoiseReadOnlyFusionSourceContractTest and a physical
model persistence expectation. They are not reported as passing or silently
removed. The historical broad-suite failures documented by the chroma task were
not repaired or reclassified here.

Debug and instrumentation APKs build and install. PhysicalLumaDeviceTest and
PhysicalChromaDeviceTest pass on ADB device `AUWE025B03006422`. The app launches
and publishes JPEGs. During instrumentation-to-app transitions an existing
`removeObserver must be called on the main thread` shutdown exception occurred;
its crash log also contains occurrences predating this task. Relaunch recovered.
Rapid debug route changes also required reopening the camera before capture.
Neither lifecycle issue was changed as part of luma processing.

See [native/GPU evidence](physical-luma/native-gpu-validation.txt).

## Real same-RAW A/B evidence

The debug control renders one immutable RAW master twice with identical metadata,
first luma Off then On. Both JPEGs and ISP diagnostics are saved under app-private
`files/physical_luma_ab/<timestamp>`. No two different exposures are used as an
A/B pair. Actual mode, source format, ISO/exposure, resident input, no CPU RGB
upload, deferred readback and JPEG 4:4:4 were checked from each pair.

All eight combinations of RAW_SENSOR/RAW10 and Malvar/AMaZE/Neural JDD/Auto Hybrid
published with Spectra Off. Both formats also published with Spectra On and
`posteriorAlreadyIncludesFusion=true`. The first attempted On/multiframe runs
that were still Off/single-frame were rejected as evidence and repeated.

The scene contains a wooden desk, mouse/mat, containers and a bright screen.
Native-resolution wood, dark mat, curved edge and screen crops were inspected.
Grain reduction is modest; substantial residual grain remains. No new halo,
edge thickening, watercolour pattern or perfectly smooth patch was apparent in
the inspected pairs. Some source edges/text are already out of focus, so these
images cannot establish retention of sensor-limit text/hair detail.

For the eight primary Spectra-Off pairs, the dark-mat JPEG local residual
variance ratio is approximately .664–.869. This residual contains **scene texture,
rendering and noise**; it is not isolated sensor-noise variance and is not used
as ground truth. Objective detail claims above use synthetic clean sources.

Per-route diagnostics, timings and descriptive patch metrics are in
[device-results.json](physical-luma/device-results.json). Raw images/logs and
crop comparisons remain locally in `work/physical_luma_validation/` (ignored).

## Performance and memory

Warm paired timestamps at 1024x1024: chroma-only colour 7.911 ms, with luma
13.916 ms, delta 6.005 ms. At 3072x4096: 98.009 -> 168.129 ms, delta 70.121 ms;
colour-call wall time including test upload/compact work 127.704 -> 200.848 ms.
The real scene gives approximately 92–115 ms extra GPU colour time. These Off
baselines include the new compact telemetry/shared-memory layout, so they measure
the marginal estimator cost rather than a rebuild comparison against the old APK.

Total RAW ISP time varies more than the GPU stage. The initial matrix includes
one +543 ms pair, with concurrent increases in unchanged provenance, tone
propagation and JPEG encoding, and negative outliers in other pairs. Do not use
those noisy totals to claim a fixed end-to-end improvement or zero overhead.
Repeated measurements are retained in [timing-repeats.json](physical-luma/timing-repeats.json).

Additional memory at W x H:

- No new persistent/transient full-frame GPU image, dispatch or full-frame readback.
- Persistent mapped statistics: `32*ceil(W/16)*ceil(H/16)` extra bytes, including
  alignment padding in 128-byte records. At 3072x4096: **1,572,864 bytes**.
- The same extra 1,572,864 bytes are read as compact workgroup telemetry per colour
  call; this is workgroup data, not full RGB. No scene-noise variance is invented.
- Shared workgroup storage grows by **6,144 bytes** to 30,720 bytes. Per-invocation
  residual arrays are shader private state; driver register/spill allocation is
  not claimed as a measured fixed byte count.
- CPU reduction grows by eight doubles (64 bytes), with no normal image copy.
- Typed CPU failure recovery may temporarily clone RGB (12*W*H bytes), reusing
  the same lifetime pattern as the existing chroma recovery; never the normal path.
- Fusion uses its already allocated compact statistics and weight buffers. Its
  newly enabled previously-skipped statistics dispatch reads 16 bytes per existing
  32x32 sample only when that former gate would have skipped measurement.

## Debug controls and reproduction

```
adb shell am broadcast -a com.bncam.SET_BENCHMARK_CONFIG -p com.bncam --ez physicalLuma false
adb shell am broadcast -a com.bncam.SET_BENCHMARK_CONFIG -p com.bncam --ez physicalLuma true
adb shell am broadcast -a com.bncam.SET_BENCHMARK_CONFIG -p com.bncam --ez physicalLumaAb true
adb shell am broadcast -a com.bncam.TRIGGER_CAPTURE -p com.bncam
adb shell am broadcast -a com.bncam.SET_BENCHMARK_CONFIG -p com.bncam --ez physicalLumaAb false
adb shell am instrument -w -e class com.bncam.PhysicalLumaDeviceTest,com.bncam.PhysicalChromaDeviceTest com.bncam.test/androidx.test.runner.AndroidJUnitRunner
```

The receiver exists only in debug sources and native release builds ignore the
bypass setter. Normal production performs one development with automatic luma.
After the initial validation A/B was disabled, luma enabled, Spectra Off and
single-frame RAW_SENSOR/AMaZE selected. That demosaic restoration was incorrect
for the main-camera comparison later supplied by the user; the main profile
was subsequently restored to NORMAL/Malvar. No profile strength slider is introduced.

## Changed files and remaining uncertainty

New files: PhysicalLumaDenoise.h, PhysicalLumaValidation.h,
physical_luma_denoise.glsl, PhysicalLumaDenoiseTest.cpp,
PhysicalLumaDeviceTest.kt, PhysicalLumaSourceContractTest.kt and this documentation.

Integration: IspCore.cpp, VulkanSpectraResidentDemosaicBackend.h/.cpp,
spectra_demosaic_resident.comp, CMakeLists.txt, native-lib.cpp, ImageUtils.kt and
BenchmarkDebugReceiverController.kt. Fusion statistics/transport:
NativeRenderQualityConfig.h, DngMerger.cpp, VulkanRawMultiFrameBackend.cpp,
raw_multiframe_fusion.comp, PhysicalNoiseCalibrationBridge.kt and its regression
fixture SensorCalibrationFusionNoiseTest.kt. `.gitignore` excludes local binaries,
captures and logs only.

Remaining limits: approximate spatial noise covariance/global fusion map;
correlated/fixed-pattern noise outside the existing model; HDR bracket-specific
real validation; additional scenes/lenses/devices; real sensor-limit text/hair
and bright-scene texture; stable end-to-end latency across thermal states.
These limits are not represented as completed acceptance checks.

## Reported regression and preview recovery

Device crash traces at 21:40 and 21:42 show the RAW preview recovery thread
inside `VulkanRawPreviewBackend::ensureLegacyPipelineLocked` and the Adreno
shader compiler. The 21:42 abort explicitly reports Scudo out of memory.
The preview backend and shaders were unchanged before these reports; the
trigger linking the observed regression to the new capture workload remains
unproven. It is not justified to dismiss these crashes as test restarts.

The recovery buffer transport now compiles `raw_preview_image.comp` with a
buffer-output define. Both image and buffer transports use the same lightweight
live processing; only the final RGBA store differs. The old capture-grade
`raw_preview.comp` is no longer compiled into the recovery pipeline. This
avoids the compiler path seen in the crashes and preserves the existing image
path. No preview resolution policy was changed.

`RawPreviewRecoveryDeviceTest` deliberately requests CPU-visible output and
validates 60 RAW_SENSOR plus 60 RAW10 frames, alpha across the full frame and
a spatial brightness step. It passes on the phone. Cold buffer pipeline
compilation was 2846.85 ms; this is not a claim of zero first-use latency.
The APK containing the fix was installed. This test does not prove that every
camera/EGL lifecycle freeze or black-bar condition is resolved.

The user's recent captures from camera IDs 2 (main), 5 (tele) and 4 (ultrawide)
all contain valid, distinct Camera2 S/O noise profiles and show both physical
chroma and luma running on the GPU. The implementation has no tele-only gate.
The original real matrix covered main and tele; ultrawide coverage was missing.
At the reported settings main and tele used AMaZE, ultrawide Malvar; Spectra
was off and capture was single-frame. Do not attribute the complaint to the
user having previously used Spectra or multi-frame: the user explicitly used
Spectra off and single-frame before the tests.

A subsequent same-RAW ultrawide A/B at the existing settings shows descriptive
high-frequency display-luma variance ratios of 0.6095 and 0.4833 in two crops.
Those crops still show substantial colour speckles. This isolates the luma
switch for that shot only; it neither establishes equivalence to the protected
installed APK nor satisfies the user's image-quality acceptance requirement.
A/B capture was disabled immediately afterward.

### Identified earlier main-camera reference

The user opened the earlier, cleaner photo in Google Photos. Its screen image
was matched to `IMG_BNC_20260926_213002_336.jpg` and its capture diagnostics.
This is main camera 2, RAW_SENSOR, single frame, Spectra Off, **luma already
enabled**, Malvar, ISO 980, 30 ms. The recent main capture at 21:49:17 used
AMaZE, ISO 2222, 16.667 ms. Thus these two photos are not an isolated before/after
luma comparison. Both the demosaic setting and captured exposure changed.
No claim is made that either difference alone quantitatively explains all noise.

The tests had left the main profile on AMaZE. Its persisted
`2_profile_1_demosaic_mode` was restored to `NORMAL` through the debug receiver
and verified in the binary settings file. BnCam was stopped afterward so its
next launch reloads the corrected setting; no foreground gallery/chat app was
opened or changed by that stop. Ultrawide already used Malvar, so this setting
repair does not resolve its remaining noise complaint.

Additional selected Kotlin checks: 10 passed, two cold-prewarm source assertions
failed. In unchanged HEAD, the test's extracted VulkanRuntime.cpp region already
contains `submissionMutex_` (which its assertion prohibits), and
VulkanRawPreviewBackend.cpp lacks the expected no-cache initialization call.
The recovery patch modifies neither file. These are existing assertion failures,
not a passing test suite.
