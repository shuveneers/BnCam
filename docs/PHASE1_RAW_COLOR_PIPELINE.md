# Phase 1 RAW color ownership

Scope: current local worktree, single-shot RAW10/RAW_SENSOR, Spectra OFF.
The pre-edit native tree is frozen under `build/phase1-color/baseline/cpp`.
Existing highlight, neutral-controls and physical-noise work is retained.

## Pre-edit boundary audit

| Boundary | Coordinates / white | Transform and clipping |
|---|---|---|
| Sensor/CFA -> demosaic | Black-subtracted, white-normalized camera samples; sensor illuminant, no RGB standard white | Existing defect/LSC/green-split processing, nonnegative RAW safety bounds; original CFA clip confidence kept separately. Demosaic and physical luma/chroma correction precede RGB WB. |
| AWB | Camera RGB, intended neutral under capture illuminant | Metadata RGGB divided by mean green; green-site ratio applied in RAW finalize. Physical AWB can subsequently change R/B even with an exact Camera2 matrix. |
| CCM | Linear sRGB/Rec.709 primaries, D65; signed and HDR | Camera2 post-WB matrix, or DNG ForwardMatrix (XYZ D50 -> Bradford D65 -> sRGB). Native adaptive arbitration can blend/rescale both matrices. No output gamut interpretation belongs here. |
| Highlight | Same signed linear sRGB D65 | Existing default all-CFA-loss confidence mixes toward equal RGB at fixed Rec.709 Y; optional calibrated DNG HSM crosses XYZ D50/RIMM. |
| Early gamut | Same primaries/white | `protectSignedCcmLowerGamut` and resident GLSL equivalent shrink chroma toward Y whenever min RGB < -1e-6. This forces the scene into the sRGB primary cone, before display rendering. |
| LOG -> FLLF | log2(max(Y, epsilon)); RGB side channel remains linear D65 | Rec.709 Y coefficients; RGB reconstructed by one positive Ynew/Yold scalar. No RGB matrix or necessary RGB component clamp. Existing shadow placement and exposure are scalar. |
| KHRONOS | Linear Rec.709 D65 -> display-linear same primaries | Existing shadow-neutral PBR variant starts with component max(RGB,0), then shoulder and highlight desaturation. Its reference input contract requires nonnegative Rec.709. |
| Profile -> output | Display-linear sRGB D65 -> encoded sRGB JPEG | Explicit tone/color controls; default hidden saturation/vibrance already disabled. Final constant-Y unit-gamut compression, numerical [0,1] clamp, sRGB OETF and quantization. |

## Findings and bounded correction

Negative sRGB coordinates alone do not prove invalid scene color. The early
constant-Y compression irreversibly changes XYZ chromaticity. It is an output
gamut operation at a scene boundary. Signed floating-point sRGB already has
sufficient coordinate range: adding different primaries is unnecessary.

Preserve signed RGB through LOG/FLLF and relocate the lower-cone operation to
the **display rendering entrance, immediately before KHRONOS**. This is the
latest boundary compatible with the existing nonnegative KHRONOS contract;
passing signed values to its max(RGB,0) would instead cause channel clipping.
The existing post-profile unit-gamut mapper remains the final output owner.
This preserves the LOG -> FLLF -> KHRONOS architecture and adds no spatial filter.

For a verified exact-frame Camera2 pair, preserve its gains and unscaled matrix,
with no native physical-AWB refinement, DNG arbitration or unpaired HSM. Metadata
provenance must distinguish an actual CaptureResult pair from static calibration.
The gain normalization is a common exposure factor only; retain that factor at
the RGB WB stage. Existing green-site calibration is part of WB ownership.

Sources: [Android CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#COLOR_CORRECTION_TRANSFORM)
defines a post-gain sensor-RGB to linear-sRGB transform (HAL FAST/HIGH_QUALITY
results are an approximation of its processing, not an absolute color guarantee).
[Khronos reference](https://github.com/KhronosGroup/ToneMapping/blob/main/PBR_Neutral/pbrNeutral.glsl)
requires nonnegative linear Rec.709 input. No claim of a unique cause of the
reported appearance is made without paired measurements and scene validation.

## Camera2 selection found during device validation

The first new main/ultrawide outdoor captures exposed a third ownership issue:
the shared Kotlin resolver rejected their numerically valid Camera2 CCM using
`evaluateExactFrameAgainstStaticCalibration`. Main had opponent-gain ratio
1.2167 and matrix distance 0.09367; ultrawide 1.1967 and 0.09115. Static DNG
characterization then replaced the exact transform and Physical AWB changed
the gains. Higher opponent-noise amplification alone does not establish that
a calibrated transform is wrong.

Default RAW now selects the validated same-frame transform at the JPEG handoff
when the gains still exactly match that frame and WB/color are automatic.
`DefaultRawCamera2PairPolicy` retains direct malformed/domain-mismatch guards,
honors explicit user WB/matrix choices, and never compares noise amplification
against static matrices. No shared resolver, Spectra, preview, YUV or multiframe
policy was changed. Native telemetry `CameraMatrixSource` and
`defaultRawExactCamera2PairPreserved` identify the actual JPEG owner; the earlier
calibration diagnostics can still describe the shared resolver's candidate.

Exact RGGB gains are factored into a mean-normalized per-site green pair before
demosaic and [R, mean(G), B] afterwards. Their product retains all four gains,
including common exposure. No residual green estimator may override that pair.
The default exact-pair path also avoids the old green-ratio clipping envelope.

## Paired real-RAW measurements

Same tele RAW_SENSOR capture `IMG_BNC_20260927_092221_182`, 4080x3072, ISO 259,
9.999993 ms; restored LSC and physical S/O. Frozen baseline is the **starting
working tree**, including the previous neutral-color fix, not repository HEAD.
Both runs execute actual production GPU shaders on Adreno 840. Stop-point
instrumentation lives only in separately compiled copies under
`build/phase1-color/{before,after}`; the installed APK is uninstrumented.

The previous fixture accidentally supplied already-refined final WB as metadata.
This comparison restores the original debug Camera2 anchors [1.93652,1,1,1.61719]
for both runs. These are exported six-significant-digit values, not a claim of
bit-exact HAL metadata recovery. Replay does not initialize the app's DNG profile
registry: it isolates native WB/gamut behavior, while live app captures and the
selection tests cover profile arbitration.

| Native stage | Negative pixels before | Negative pixels after |
|---|---:|---:|
| Post-WB | 5,164 | 5,164 |
| Post-CCM | 51,977 | 53,334 |
| Post-highlight/early gamut | 0 | 53,334 |
| Post-FLLF | 2 (numerical tolerance) | 53,334 |
| Post-KHRONOS | 0 | 0 |
| Post-final gamut | 0 | 0 |

Threshold min(RGB) < -1e-6; total 12,533,760 pixels, no nonfinite values.
Different post-CCM counts reflect the WB fix. Before WB was
[1.9177357,1,1.6717854]; after [1.93652,1,1.61719]. CCM is identical in this
isolated replay: [218,-71,-19; -14,176,-34; 11,-95,213]/128.

On the identical **old** post-CCM foliage patch, 60/20,480 pixels invoke the
early compression; 38 of those have positive XYZ coordinates. Negativity alone
therefore cannot justify declaring all these samples invalid. On the 60 affected
pixels, mean xy displacement is 0.07970, opponent chroma ratio 0.85852 (minimum
individual scale 0.43942), luminance delta ~0, and RGB-opponent hue rotation 0°.
This last metric is the angle of (R-G,B-G), not a perceptual hue/accuracy measure.
Positive XYZ alone also does not establish a spectrally realizable/noise-free color.
The independent Rec.2020-green test supplies a known valid-color counterexample.

Moving constant-Y cone compression across a scalar luminance reconstruction can
commute mathematically. Therefore its relocation preserves scene information
but does **not** by itself prove visible improvement. The WB ownership change
is the principal final-pixel difference in this paired replay.

| Patch | Final mean xy displacement | Mean absolute opponent hue change | Chroma after/before | Mean linear Y delta |
|---|---:|---:|---:|---:|
| Blue hood | 0.008062 | 0.651° | 0.8963 | +0.000916 |
| Foliage | 0.008092 | 3.343° | 1.0752 | +0.000407 |
| Neutral wall | 0.007961 | 7.875° | 1.1718 | +0.000463 |
| Sky | 0.008060 | 3.711° | 0.7256 | +0.035581 |

This is not blanket saturation reduction: foliage/wall chroma increases.
All 53,888 sampled pixels are bit-identical before WB, both post-demosaic and
post-existing-denoise. No demosaic, denoise, physical luma/chroma algorithm,
Edge/Detail, sharpening or new spatial filter was changed. Per-pixel color
changes intentionally mean final JPEG gradients need not be numerically identical.

Complete per-boundary patch data: `build/phase1-color/stage-color-metrics.csv`;
counts/transforms/isolated comparisons: `build/phase1-color/color-results.json`.
Recompute with `python app/tools/analyze_phase1_scene_color.py` (numpy required).

## Tests and changed files

- Debug APK built and installed with `adb install -r`.
- 14 selected Kotlin tests passed (matrix validation/math and the default pair
  policy, including explicit-control/scope/missing-pair guards).
- Production Vulkan RAW10/RAW_SENSOR synthetic configurations passed on Adreno
  840; maximum CPU/GPU color and tone difference 2.38419e-7. Legacy Spectra/YUV
  shader fixtures are bit-identical to the pre-existing reference.
- Native signed-scene/display-entry/exact-matrix test passed on device.
- One additional pre-existing source-contract test fails because it expects
  `Color Matrix Metadata Pre-Normalization Values`; that string is absent from
  unchanged HEAD `SensorCalibration.kt` too. It was not rewritten to hide this.

Production files changed **by this task**: `IspCore.cpp`,
`DefaultRawColorPipeline.h`, `HighlightGamutProtectionV2.h` (comment only),
`VulkanSpectraRawFinalizeBackend.cpp/.h`, `spectra_raw_finalize.comp`,
`spectra_demosaic_resident.comp` (color stage only), `spectra_tone_resident.comp`,
`ImageUtils.kt`, `DefaultRawCamera2PairPolicy.kt`.
Validation changes: two new policy tests, `DefaultRawColorHighlightDeviceTest.cpp`,
the existing prepare/run stage-audit tools, `analyze_phase1_scene_color.py`, this report.
Other starting local changes remain intact. No commit or push was made.

## Live device scenes and unresolved acceptance

The user made main/ultrawide/tele outdoor and main/tele indoor captures on the
installed APK. After the final Kotlin selection fix, repeated main and ultrawide
captures (`143700_632`, `143709_928`) both report the exact Camera2 matrix,
unmodified gains, exact weight 1, DNG weight 0, GPU tone, and zero early gamut
compression. Main has 472,668 negative post-CCM pixels; ultrawide 49,243.
Tele outdoor (`143007_948`) reports 111,037 and zero early compression, with
the same exact-pair architecture. Indoor main/tele also use the exact pair;
no obvious gross indoor color failure appeared in the reviewed images.

**Visual acceptance has not passed.** The user's magnified screen shows strong
cyan/green/magenta edge zipper, pink highlights and harsh foliage colors.
The screenshot is saved at `build/phase1-color/device/current-screen.png`.
Inspection must not be replaced by the passing numerical ownership tests.

Direct original-CFA measurement of that tele image locates the pink highlights:
in the van-roof ROI [2100,1315,100,22], 83.64%/82.91% of the two green planes
reach the clip guard (median 1.0), while R/B have zero clipped samples. In the
pink roof ROI [1860,996,70,22], green clipping is 67.79%/67.53%, R/B zero.
Coordinates are in rotated JPEG space; CFA parity was mapped in original sensor
coordinates. Results: `device/screen-patch-cfa.json`.
The existing all-four-CFA-loss highlight policy retains confidence 1 for this
partial clipping, even though green information is lost. The scene-color fix
does not reconstruct that missing information. The wall/foliage sample ROIs
have no sensor-clipped samples. Edge false color and spatial reconstruction
remain excluded from this phase; no smoothing or heuristic desaturation was added.

The user additionally reported a live-preview color change about two seconds
after loading a sensor. Audit found bootstrap uses identity CCM with stabilized
WB, then asynchronous configuration changes the matrix; the refresh interval
is 1500 ms. The default preview also preferred the temporal Physical-AWB pair
over the exact timestamp pair. In response to that observation, RAW preview
bootstrap now waits for a valid Camera2 pair instead of displaying sensor RGB
as sRGB, and default single/Spectra-OFF steady preview uses the same exact-pair
selection as capture. Explicit WB remains authoritative. Bootstrap is a
temporary RAW preview only; Spectra processing, YUV and fusion are unchanged.
Additional files: `BnCameraManager.kt`, `RawPreviewRenderer.kt`.
The preview/capture APK compiled and installed. Two additionally run legacy
`RawPreviewLiveColorContractTest` source assertions expect the removed
`updateLiveColorTuning` API; both strings are absent from repository HEAD too.
The 14 matrix/pair tests still pass. Those existing source-test failures remain
reported, not silently redefined. On-device visual transition verification is
tracked separately from compilation.

### Resolved preview transition: delayed frame metadata

The user reported that the first preview change still jumped. Logging the actual
resolved renderer pair, rather than just configuration, showed `CONFIG_FALLBACK`
on every sampled frame. At age 2148 ms main switched from R/B gains
2.772461/1.3632812 to 2.546875/1.484375 with a new CCM. The exact metadata had the
correct generation, sensor, source and timestamp, but arrived after the image.
The 20 ms grace plus latest-image replacement discarded images before their
matching result arrived. Increasing only the grace would still starve matching
older frames as newer images continuously replaced them.

Default RAW preview now retains a bounded four-image pairing window (150 ms
deadline), selects the newest timestamp-complete WB/CCM pair, and releases expired
or superseded images. Missing metadata no longer renders stale config color on
this path. Other routes retain latest-only submission. Capture-ring ownership,
pixel processing and spatial filters are unchanged. New helper/tests:
`RawPreviewPendingFrames.kt`, `RawPreviewPendingFramesTest.kt`.

On-device final sampled logs contain 102 `EXACT_TIMESTAMP` observations across
four activations of sensors 2/5, with zero config fallbacks, fatal exceptions or
slot-starvation messages. The first paired main preview was rendered at 288 ms
after config activation. This is sampled evidence, not a complete per-frame
latency benchmark or a tele-specific verification. The user tested the installed
version and explicitly confirmed: **“Abrupte omslag is weg.”**
All 17 selected JVM tests pass (pair policy 3, exact matrix 7, metadata cache 3,
pending pairing 4). The latter cover image-before-metadata arrival, newest complete
selection, capacity/expiry, retirement and legacy latest-only behavior. The APK
build/install succeeds. Temporary per-result cache tracing was removed; bounded
debug startup owner logging remains. Evidence:
`build/phase1-color/device/preview-metadata-logcat.txt`,
`preview-paired-final-logcat.txt`, `work/phase1-preview-paired-tests.log`.
This confirms the preview transition fix; it does not supersede the unresolved
outdoor visual acceptance and partially clipped highlight findings above.

The uninstrumented production replay JPEG is byte-identical to the instrumented
final-stage JPEG (SHA256 `11d1237a2e7c8ddee9aa5a56598f72639543ebfe8adb6dd535d737513e4a3340`).
The full metrics CSV is retained in `docs/PHASE1_RAW_COLOR_METRICS.csv` as well.
