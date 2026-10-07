# Single-frame RAW replay

The production capture pipeline remains CameraScreen → BnCameraManager → CaptureRecipe /
generation-scoped FrameRingBuffer → SingleFrameRunner → SingleRaw16FrameBuilder →
ImageUtils → production JNI → RawDomain / IspCore → processing/save queues → MediaStore.

The existing `DefaultRawSceneReplay.cpp` is now the `bncam_raw_replay` CMake target
(excluded from the ordinary APK target). It calls `normalizeRawForJpeg` and
`IspCore::renderRawBaselineJpeg`; there is no alternate ISP implementation.
Build it with the existing configured Android CMake build directory:

```
cmake --build app/.cxx/Debug/<current-config>/arm64-v8a --target bncam_raw_replay
```

The executable needs an Android device and the matching `libbncam.so`, OpenCV and
C++ runtime libraries on its library path. Supply the same fixture directory for
each comparison:

```
LD_LIBRARY_PATH=. ./bncam_raw_replay fixture malvar 1 1
LD_LIBRARY_PATH=. ./bncam_raw_replay fixture amaze 1 2
LD_LIBRARY_PATH=. ./bncam_raw_replay fixture hybrid 1 0
```

The third argument is the existing single-shot flag: `1` uses single-frame policy
with Vulkan initialization, `2` suppresses Vulkan initialization for CPU fallback
investigation. CPU fallback is not a validation of the regional Hybrid GPU blend.

## Fixture files

- `raw.bin`: canonical unpacked little-endian RAW16 code values, before normalization.
- `fixture.txt`: width height sensor-CFA ISO source-format exposure-ms white;
  four positional black levels; four WB gains; nine row-major CCM values.
  Source-format is `0` for unpacked RAW10 and `1` for RAW_SENSOR.
- Optional `geometry.txt`: CFA-origin-X CFA-origin-Y RAW16-row-stride-bytes.
  Without it, existing fixtures keep origin zero and tightly packed rows.
- Optional `capture-metadata.txt`: existing signal-confidence; four S/O pairs;
  LSC rows/columns; row-major LSC values. These restore actual noise/LSC authority.

Black levels are positional sensor-origin values; geometry shifts CFA and black
phase together. RAW16 byte size must match geometry exactly. Malformed files fail
with a message in both debug and release, without relying on `assert`.

Materialize the checked-in synthetic fixtures:

```
python app/tools/prepare_single_frame_replay.py app/src/test/fixtures/raw/odd-crop-grbg.json build/replay/odd-crop-grbg
python app/tools/prepare_single_frame_replay.py app/src/test/fixtures/raw/tiny-bggr.json build/replay/tiny-bggr
python app/tools/test_single_frame_replay.py
```

The generated manifest fingerprints input bytes. Synthetic fixtures isolate
geometry and use identity WB/CCM, with no invented captured noise/LSC calibration.
Real fixtures can be placed outside Git under `build/replay/device/<scene>/`.
Existing device exports under `build/default-raw-scene/` remain supported. Keep
camera ID, timestamp, actual exposure, orientation, crop and sensor calibration
alongside a real export. This compact replay format does not serialize every
live AF/person/temporal/SPECTRA observer hint or all profile settings; those must
be explicitly frozen before claiming full device-output reproduction.

## Native regressions

`ImageUtils.validateDemosaicImplementation()` also runs
`validateSingleFrameRawReplay()` through the existing instrumentation test.
It checks 80 combinations of four CFA patterns, four crop parities and five
geometries (including 1×1), production normalization, black/white endpoints,
finite borders, sample preservation, repeated and concurrent result ownership,
a numeric MHC impulse golden, and deterministic Hybrid policy. It reports the
maximum Malvar/AMaZE pixel difference without treating algorithm differences as
failures. Run on device:

```
gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bncam.DemosaicNativeValidationTest
```
