# Detail-first recovery: model correction without pixel correction

Baseline: `8aee2d298bffb2daf9f1640e7ff654ab8c7a46bc` (`main`), 2026-09-27.
Local branch: `codex/physical-noise-confidence`.
This report supersedes the rejected adaptive-denoise experiment. This is not a
new denoise version and does not claim improved photographic noise removal.

## A. Model, covariance, provenance and diagnostics only

`PhysicalNoisePropagation.h` independently predicts fixed bilinear/Malvar
covariance from products of kernel coefficients at the **same CFA sample**.
The baseline energy-root colour transform does not represent actual overlap.
For Malvar, the corrected cross-channel covariances are:

| Covariance | coefficient of raw R variance | G variance | B variance |
|---|---:|---:|---:|
| R,G | 0.125 | 0.3125 | 0.1171875 |
| G,B | 0.1171875 | 0.3125 | 0.125 |
| R,B | 0.1875 | 0.2109375 | 0.1875 |

The independent test reconstructs impulse responses from the fixed stencils in
`spectra_demosaic_resident.comp`, sums overlap for all four CFA phases and checks
all nine entries for equal/unequal variances, including very small variance.
Marginal variances and opponent projections follow from that covariance.

This calculation is exact only for the phase-averaged **interior**, linear fixed
kernels with independent, locally stationary CFA noise. It does not establish
exactness at borders, after clipping, for correlated noise or adaptive
demosaicers. Unsupported/correlated input gets no new valid diagnostic
prediction. The propagation helper's fallback returns the original state.

`NoisePrediction` retains the actual existing post-demosaic confidence and its
existing uncertainty discount. It does not replace confidence with a boolean
or promote it to 1. Invalid/unavailable evidence produces no valid diagnostic
prediction. Source confidence is retained even when invalid; valid confidence
is copied exactly into the corrected prediction.

`IspCore.cpp` has only an include and one diagnostic stream insertion. The new
`physicalNoisePredictionOnly` field explicitly says `appliedToPixels=false` and
`filterBaseline=8aee2d2`. It is disabled for Spectra, neural posterior and Auto
Hybrid output. Original states remain unchanged. Corrected covariance and
confidence are not assigned to chroma/luma models, Vulkan requests, shared
Spectra state, white balance or downstream tone state. The physical prediction
is corrected **in the separate diagnostic model**, not in filter inputs.

Test-only additions are CMake/CTest registration, a host/ADB runner, independent
impulse/provenance/invalid-evidence tests, frozen-reference RGB comparisons and
production-source isolation checks.

## B. Changes that affect RGB pixels

**None relative to `8aee2d2`.** Removed experimental changes include:

- New adaptive `noisePressure`, changed luma and chroma authority.
- Coherent-chroma lifting/filtering and changed neighbour handling.
- Feeding corrected covariance or new confidence weighting into filters.
- Changed invalid spatial-evidence handling in CPU/GPU filters and GPU upload.
- Associated experimental validation changes and camera capture tools.

CPU filter headers, authority helper, GPU filters, resident demosaic shader,
GPU transport, JNI and shared covariance propagation match the baseline. ISP
pixel wiring matches after removing the two allow-listed diagnostic blocks.
The baseline's **own** adaptive pressure and authority remain exactly as they
were in `8aee2d2`; disabling them would break the requested identity.

CPU/GPU invalid-evidence parity changes are deferred because changing their
bypass/clamping decisions can change pixels. This recovery does not fix every
pre-existing baseline behavior. Continuous chroma confidence weighting is also
deferred: confidence is available separately but not newly used as authority.

No source changes to Spectra, YUV, tone mapping, lens tuning, demosaic
reconstruction or preferences. Scope is single-shot RAW10 / RAW_SENSOR with
Spectra Off.

## Verification

Eight CTest checks pass on the attached Android arm64 device:

1. Original `AdaptiveNoiseAuthorityTest`, registered without changing its contract.
2. `PhysicalNoisePropagationTest`: impulse covariance, exact confidence retention,
   invalid/unavailable evidence and unsupported-domain rejection.
3. `PhysicalNoisePixelIdentityTest`: **460,800 bit-exact RGB comparisons** against
   filter headers read directly from the frozen Git commit during CMake
   configuration. Chroma output and subsequent luma output both match. The
   diagnostic covariance actually differs, so this is not an unchanged-model
   no-op. Cases include dark/bright signal, 1px/2px lines, weak texture, diagonal
   hair, equal-luma colour edges, dark colour texture, colour patterns and weave;
   three noise levels, five confidence values and three spatial-map cases.
4. Original `PhysicalChromaDenoiseTest`.
5. Original `PhysicalLumaDenoiseTest`.
6. `PhysicalNoiseNativeContractTest`.
7. `RawCfaLevelMappingTest`.
8. `PhysicalModelIsolationSourceTest`: exact production-source comparison with
   only the two specific ISP diagnostic blocks permitted.

Debug app and instrumentation APK builds pass. The restored app replaces the
experimental APK on the device. All three device tests pass: production CPU/GPU
luma, production CPU/GPU chroma and RAW preview recovery (60 RAW10 plus 60
RAW_SENSOR frames). These use the original baseline assertions, not the
rejected experiment's assertions. Results are recorded in
`work/physical_luma_validation/recovery/device-tests.log`.

Reproduce from the repository root:

```powershell
python app/tools/run_physical_noise_tests.py --sdk C:/Users/shuve/AppData/Local/Android/Sdk --build-dir build/physical-noise-recovery
# Host alternative, with CMake and a C++17 compiler:
python app/tools/run_physical_noise_tests.py --build-dir build/physical-noise-host

./gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
adb shell am instrument -w -e class com.bncam.PhysicalLumaDeviceTest,com.bncam.PhysicalChromaDeviceTest,com.bncam.RawPreviewRecoveryDeviceTest com.bncam.test/androidx.test.runner.AndroidJUnitRunner
```

This proves CPU correction identity and unchanged GPU code/wiring; it is not
an end-to-end bitwise comparison of two complete camera APKs or proof that the
baseline preserves every photographic detail. No new lens capture matrix or
photographic quality acceptance is claimed. Synthetic MSE is not acceptance
evidence for future denoise. No broad JVM-suite pass is claimed; unrelated
baseline source-contract failures from the earlier investigation were not
changed or hidden.

Logs and the rejected experimental diff are retained in the ignored
`work/physical_luma_validation/recovery/` directory. Previous images/metrics in
`adaptive/` describe the rejected version, not this recovery.

## Mandatory rule for subsequent pixel work

Detail preservation takes priority over removing noise. Isolate each candidate
in a separate small change. First measure identical RAW inputs against the
baseline, using real captured structure as well as synthetic controls. Keep
exposure/processing fixed; use registered high-SNR references or repeated
fixed-scene captures to distinguish noise from structure. Keep single shot,
RAW10/RAW_SENSOR and Spectra Off. Report per scene, region and available lens;
do not conceal a local regression in an aggregate mean.

Before accepting any pixel correction, measure at least:

- Local contrast retention.
- 1px and 2px line amplitude.
- Fine texture amplitude.
- Edge sharpness and edge width.
- Chroma edge amplitude, including nearly constant-luma edges.
- Weak, low-contrast texture.
- Detail in dark surfaces, including colour texture.

If a candidate demonstrably reduces **any** real structure, reject it even if
it removes more noise or improves MSE. Unresolved measurement uncertainty is
not proof of detail preservation. Never compensate with sharpening afterwards.
Only after this recovery may chroma improvements be considered separately.
