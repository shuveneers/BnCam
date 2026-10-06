# Default single-shot RAW color/highlight audit

Status: implementation, device regressions and the paired native GPU stage audit
completed. The supplied tele capture confirms that the hidden chroma gain is
gone without changing demosaic, existing denoise, WB or CCM. Real main/ultrawide
scene captures and absolute colorimetric accuracy remain outside the measured evidence.

## Scope and causes

Only the explicitly identified RAW10/RAW_SENSOR single-shot route with Spectra
OFF selects the new policy. JNI recognizes the existing `_SINGLE` working-source
labels. Multi-frame, Spectra and YUV retain the legacy policy.

The old neutral RAW render multiplied chroma by
`1 + adaptiveSaturationBoost + adaptiveVibranceBoost`, even at zero profile
controls. In midtones this can approach 1.42 in chroma amplitude (about 2.0164
in variance for locally constant gain). This is a formula bound, not a measured
gain on this capture. The boost is bypassed in both CPU and GPU default RAW.
Explicit Saturation, Vibrance, Pop and Color Recovery remain user-controlled.

Old highlight confidence used a minimum/mean loss beginning around 0.94 and a
hard-clipping decision around 0.99, then spread low confidence into neighboring
CFA cells. The default policy now uses the product of the four original CFA
sample losses, each smooth between 0.985 and 1.0. Any sample below this guard
preserves full chroma confidence. Both green samples are considered separately.
Interpolation affects evidence only; an intact owning cell cannot lose confidence
because of clipped neighbors. This preserves available chromaticity, but does
not claim to recover the true value of a clipped channel.

The scene classifier previously allowed rectangular bright regions plus a low
RAW median to dominate its indoor-display evidence. Default RAW now requires
supporting exposure/ISO evidence and rejects edge-connected display islands.
This changes a tone-driving confidence, not just the reported label. Missing
metadata remains ambiguous; the classifier does not force an outdoor label.

## Flow and unchanged processing

Old: original CFA confidence -> demosaic/existing physical denoise -> WB ->
signed CCM -> early highlight neutralization -> characterization/lower gamut ->
LOG/FLLF/KHRONOS -> hidden automatic chroma gain -> explicit profile -> gamut.

New default: original CFA per-sample loss -> same demosaic/existing physical
denoise -> same WB and signed CCM -> conservative all-sample-loss highlight mix
-> characterization/lower gamut -> same LOG/FLLF/KHRONOS -> explicit profile
-> same final gamut. CPU fallback now uses original CFA confidence as well.
Its pre-existing lack of FLLF remains a degraded fallback limitation; complete
CPU/GPU JPEG identity is not claimed.

No denoise algorithm, denoise strength, WB gain, CCM coefficient, matrix
arbitration, FLLF algorithm or sharpening control was changed. Existing physical
noise-model-only changes present in the worktree before this task were preserved.

Matrix audit of the supplied capture: Camera2's exact-frame transform dominates
the current arbitration (about 0.997284 Camera2 versus 0.00271647 DNG). The
Camera2 matrix is `[218,-71,-19; -14,176,-34; 11,-95,213]/128`, with row sums
1, 1 and 1.0078125. These facts do not prove absolute colorimetric accuracy:
there is no chart/reference illuminant in this scene. No matrix is weakened to
hide noise, and no architectural change is justified by these measurements.

## Completed validation

- Android debug assembly passed (`work/default-raw-final-build.log`).
- Actual Adreno 840 execution of full current and frozen-HEAD shaders passed.
  Six synthetic configurations exercise three lens-like WB/CCM sets and both
  RAW10/RAW_SENSOR quantization. These are not six real camera captures.
- Maximum CPU/GPU error: source confidence 0; color 2.38419e-7; tone 2.38419e-7.
  8,928 expected unchanged pixels preserved exactly; 2,838 fully lost-information
  synthetic pixels neutral. All clipping masks, unequal greens, signed CCM,
  neutral profile controls and continuous highlight ramps are covered.
- Frozen baseline `8aee2d298bffb2daf9f1640e7ff654ab8c7a46bc`: legacy raw finalize,
  color, Spectra tone and shared YUV shader output bit-identical in the regression
  fixtures (`work/default-raw-device-tests.log`). Shared files named `Spectra`
  contain the guarded default-RAW branch; Spectra behavior was not changed.
- Selected Kotlin/source tests: 20/22 passed. Two assertions already disagree
  with HEAD: Phase5ToneArchitectureSourceContractTest expects a one-argument
  applyProfileColor call; Phase4SensorColorNeutralitySourceContractTest expects
  getElement in only one Kotlin owner. Native adaptive color authority, DNG
  illuminant calibration and Phase10 profile color tests passed. Older native
  characterization/profile tests refer to removed fields, and the FLLF policy
  test retains an obsolete scene-key bound. These unrelated tests were not edited.

## Supplied scene: preliminary paired replay

Capture `IMG_BNC_20260927_092221_182`, camera 5/tele 3.7x, ISO 259,
9.999993 ms, 4080x3072 BGGR RAW_SENSOR, single shot/Spectra OFF. An earlier
091713 capture was MultiFrame/Spectra On and was excluded from the fix evaluation.

Files live under `build/default-raw-scene/single/`. The first before/after replay
used identical RAW, WB/Camera2 transform and controls, but did not transport the
capture's LSC and physical S/O snapshot. Therefore those JPEGs are an isolated
color-policy comparison, **not** an exact reproduction of the app's remaining
noise. Their results must not substitute for the completed full-metadata audit below.

- Original CFA scan: 3,133,440 cells; old reduced-confidence cells 1,082 versus
  new 116. All 760 partial-clipping cells retain confidence 1 under the new rule.
- Partial/full sensor-clip 2x2 cell pixel coverage: 3,040 / 464. These counters
  mean cell coverage, not the number of individually saturated photosites.
- Actual chroma-neutralization pixels in preliminary replay: 7,342 -> 464.
- KHRONOS highlight-region pixels: 83,893; maximum absolute linear-luma
  compression 0.811864. This is not a clipping count or a noise measurement.
- Classification: indoor_display_highlight -> localized_highlight.
- Rough JPEG foliage/grass/blue-lacquer saturation means decrease from
  0.4746/0.6607/0.7017 to 0.3934/0.5285/0.6306. Luma-gradient RMS ratios are
  1.00042/0.99011/0.99551. These JPEG measures support reduced oversaturation;
  they cannot prove exact preservation of all sensor detail.
- No skin appears in this scene. Real main/ultrawide scene validation remains
  unperformed; only the synthetic lens configurations cover them so far.

## Completed chroma residual audit

Instrumentation is confined to a generated source copy under
`build/default-raw-stage-audit/src`; the normal APK is not instrumented or installed.
The instrumented native library compiled successfully.

The original debug provides all eight S/O doubles and confidence 1.0; the DNG
contains the 13x17x4 lens-shading map, gains 1.0009765625 to 2.359375.
`prepare_default_raw_capture_metadata.py` restores both into the replay, retaining
existing physical denoise unchanged. GainMap parsing follows Android's
[DngUtils.cpp addGainMap implementation](https://android.googlesource.com/platform/frameworks/av/+/e743a47/media/img_utils/src/DngUtils.cpp).
WB/Camera2 values remain the exported replay fixture values, not a claim of
byte-identical reconstruction of every original app metadata field.

The audit records identical fixed-coordinate patches at post-demosaic,
post-existing-denoise (extra boundary to avoid confounding), post-WB, post-CCM,
post-highlight/characterization/lower-gamut, post-FLLF, post-KHRONOS,
post-automatic-color and post-final-gamut. WB/CCM samples are computed from
the actual captured pre-WB buffer and actual transform floats; other boundaries
are native shader float readbacks, before gamma/JPEG. The analysis asserts
identical WB/CCM, demosaic and existing-denoise outputs between old and new.

Fixed patches: dark glass, neutral wall, white van, sky, blue hood, foliage and
a near-clipping region. Foliage and near-clipping patches are textured controls;
their residual variance must not be described as pure sensor noise.

For each patch/stage, fit and subtract a 2-D quadratic color gradient separately
within identical non-overlapping 16x16 supports. This is analysis only, with no
pixel mutation in the render. Covariance uses N minus six fitted parameters per
tile. Report sigma(R-G), sigma(B-G), covariance(R-G,B-G), patch mean chroma,
trace of residual covariance and stage-to-stage sigma/variance gains. Also
report mean luma and variance gain divided by squared mean-luma gain to separate
brightness changes from added chroma gain. That normalization is diagnostic,
not a proof that all residual structure is sensor noise. A 32x32 sensitivity run
can expose dependence on detrending scale.

Pointwise deltas across the neutral automatic-color and final-gamut boundaries
are recorded separately. Neither WB nor CCM amplification will be classified as
an error solely because it increases variance.

Reproduce the completed audit:

```powershell
$env:PYTHONPATH = "$PWD/build/default-raw-python"
python app/tools/run_default_raw_stage_audit.py --sdk C:/Users/shuve/AppData/Local/Android/Sdk --fixture build/default-raw-scene/single
python app/tools/analyze_default_raw_stage_audit.py build/default-raw-stage-audit
python app/tools/analyze_default_raw_stage_audit.py build/default-raw-stage-audit --tile 32
```

Outputs are in `build/default-raw-stage-audit`: `stage-residuals-tile16.csv/json`,
`stage-residuals-tile32.csv/json`, `old/new-full-metadata.jpg`, and the fixed-patch
preview `patches.jpg`. The complete 16x16 result table is also retained as
[DEFAULT_RAW_STAGE_RESIDUALS.csv](DEFAULT_RAW_STAGE_RESIDUALS.csv).

The initial diagnostic attempt invalidated resident GPU ownership after a float
readback and was rejected: it had run CPU tone. The instrumented copy now
preserves both GPU ownership and readback visibility. The runner requires
`vulkanToneUsedForOutput=true` and actual tone buffers. Only the corrected GPU
runs are represented in the tables below.

A separate replay using the uninstrumented production library produced byte-for-byte
identical JPEGs to the instrumented final stage for both variants. SHA256:

- Old: `ab64c7b9c1640f94308eac2a852f8461815da8d8ac5893b4267bade71b76b4fe`
- New: `29be31e54eaf9ecda4ae609eb2f5b257e2bc632e863bff95e4c13f83ab33f1f5`

Demosaic and existing-denoise patch buffers, WB gains and CCM coefficients are
bit-identical between old and new. No noise reduction was added or strengthened.
The comparison uses one captured RAW, not independent noisy acquisitions.


## Measured amplification and remaining residuals

All values below are linear RGB before gamma/JPEG. Variance means
`var(R-G) + var(B-G)` after local quadratic detrending. Gains are relative
to the immediately preceding stage; post-demosaic is the starting boundary.

### New render: variance gain per boundary

| Patch | Existing NR | WB | CCM | Highlight/lower gamut | FLLF | KHRONOS | Automatic color | Final gamut |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| dark_glass | 0.7453 | 2.7354 | 3.7432 | 1.0000 | 2.0592 | 1.0000 | 1.0000 | 1.0000 |
| blue_hood | 0.9799 | 1.9381 | 3.7072 | 1.0000 | 3.2556 | 1.0000 | 1.0000 | 1.0000 |
| white_van | 0.9999 | 1.4480 | 3.6371 | 1.0000 | 0.8689 | 0.8037 | 1.0000 | 1.0000 |
| neutral_wall | 0.9976 | 2.2751 | 3.5520 | 1.0000 | 1.1324 | 1.0000 | 1.0000 | 1.0000 |
| sky | 0.9985 | 2.7399 | 3.7442 | 1.0000 | 0.9490 | 0.4483 | 1.0000 | 1.0000 |
| foliage | 0.9941 | 1.2721 | 3.4960 | 0.9906 | 2.7145 | 1.0000 | 1.0000 | 1.0000 |
| near_clip | 0.9997 | 0.2018 | 3.3557 | 1.0000 | 1.1044 | 0.7231 | 1.0000 | 1.0000 |

WB and CCM propagate physical chroma residuals substantially; they remain
unchanged. These gains alone do not establish a color-transform error. In the
near-clip patch WB reduces opponent residual variance because part of the
uncorrected sensor color structure becomes neutral after WB.

### Automatic color and total remaining variance

| Patch | Old automatic variance gain | New automatic gain | Final new/old variance | Reduction |
|---|---:|---:|---:|---:|
| dark_glass | 1.1235 | 1.0000 | 0.8901 | 11.0% |
| blue_hood | 1.6453 | 1.0000 | 0.6078 | 39.2% |
| white_van | 1.7219 | 1.0000 | 0.5808 | 41.9% |
| neutral_wall | 1.8337 | 1.0000 | 0.5454 | 45.5% |
| sky | 1.8504 | 1.0000 | 0.5404 | 46.0% |
| foliage | 1.6359 | 1.0000 | 0.6194 | 38.1% |
| near_clip | 1.7068 | 1.0000 | 0.5864 | 41.4% |

### Final new-render residual values

| Patch | sigma(R-G) | sigma(B-G) | covariance | mean(R-G) | mean(B-G) | Residual variance |
|---|---:|---:|---:|---:|---:|---:|
| dark_glass | 0.00418768 | 0.00433251 | 9.84634e-06 | -0.00583566 | 0.00396269 | 3.63073e-05 |
| blue_hood | 0.007141 | 0.00782208 | 2.40884e-05 | -0.0205148 | 0.11626 | 0.000112179 |
| white_van | 0.0165817 | 0.0149954 | 8.33448e-05 | -0.0809216 | 0.254635 | 0.000499814 |
| neutral_wall | 0.0199342 | 0.0139002 | 0.000139795 | 0.0585738 | -0.0237322 | 0.000590587 |
| sky | 0.0104189 | 0.00908102 | 4.90092e-05 | -0.0575063 | 0.187676 | 0.000191019 |
| foliage | 0.0183984 | 0.0140941 | 7.85514e-05 | -0.00732313 | -0.0310765 | 0.000537146 |
| near_clip | 0.0195555 | 0.0189192 | -5.60379e-05 | -0.018839 | 0.0924882 | 0.000740354 |

The CSV includes these same metrics and sigma/variance gains at **every** stage,
for both variants, along with mean luminance and luminance-normalized variance.
At 32x32 detrending supports, the final new/old variance ratios are 0.8928
(dark glass), 0.6117 (blue hood), 0.5820 (white van), 0.5528 (wall), 0.5406
(sky), 0.6177 (foliage), and 0.6137 (near clipping). The conclusion persists
across detrending scales; the textured near-clip control is more scale-dependent.

### Tone/gamut attribution

- FLLF's RGB output matches a common per-pixel luminance scale to within
  1.11e-7 across these patches. No extra channel-dependent gain was found.
  Its variance gain divided by squared mean-luma gain is 0.9668 dark glass,
  0.9995 blue hood, 1.0157 white van, 0.9024 wall, 0.9998 sky, 0.8538 foliage,
  and 0.9717 near clipping. Small departures from unity include spatial tone
  variation and real texture, rather than a hidden saturation multiplier.
- KHRONOS does not increase pointwise opponent-chroma magnitude beyond float
  roundoff (maximum ratio 1.00000017). It reduces residual variance to 0.8037x
  in white van, 0.4483x in sky and 0.7231x near clipping; it leaves dark glass,
  blue hood and wall at 1.0000x. This preserves the existing tone architecture.
- With neutral profile controls, the automatic-color boundary is identity to
  1.1921e-7 maximum RGB difference, including neutral tone-LUT roundoff.
  Its measured variance gain is 1.0000x in every new-render patch.
- Final gamut is **bit-identical** to its input in all seven new-render patches.
  There is no observed residual-to-colored-blob amplification in these samples.
  This does not claim exhaustive behavior for every possible out-of-gamut color.

### Full-metadata highlight and visual validation

The corrected full-metadata render neutralizes 464 pixels, down from 7,304 in
its paired legacy render. Partial/full CFA-cell coverage remains 3,040/464.
It reports 102,975 KHRONOS highlight-region pixels and maximum absolute linear
luma compression 1.10104. Classification changes from indoor_display_highlight
to localized_highlight. These supersede the preliminary no-LSC/no-SO replay
numbers for the final comparison.

Full-resolution renders and comparison:
`build/default-raw-scene/single/full-before.jpg`, `full-after.jpg`, and
`full-comparison.jpg`. Visual inspection shows reduced saturation in foliage,
blue paint and red paint, with visible fine foliage and surface structure.
No new spatial filter or sharpening compensation was introduced. Exact
upstream pixel identity is verified; JPEG-level identity of detail cannot be
claimed when chroma and highlight rendering intentionally change.

The remaining residuals are real measured image residuals, not automatically
pure sensor noise. Dark glass and sky are the strongest flat-patch evidence;
foliage, wall texture, vehicle reflections and the near-clip strip retain scene
structure after detrending. A repeated capture/flat-field experiment would be
needed for an unbiased temporal sensor-noise estimate. These results establish
that color-pipeline amplification was substantial and has been removed at the
automatic-color stage. They do not justify strengthening denoise in this task.

## Changed files

Production: IspCore.cpp/.h, native-lib.cpp, HighlightGamutProtectionV2.h,
DefaultRawSceneEvidence.h, the RawFinalize/ResidentDemosaic/ResidentTone Vulkan
backend headers/implementations and their three shaders.

Validation: DefaultRawColorHighlightDeviceTest.cpp, DefaultRawSceneReplay.cpp,
run_default_raw_color_tests.py, prepare_default_raw_capture_metadata.py,
prepare_default_raw_stage_audit.py, run_default_raw_stage_audit.py,
analyze_default_raw_stage_audit.py and this report.
