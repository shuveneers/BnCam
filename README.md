# BnCam

Android Camera2 camera pipeline with explicit YUV, RAW10 and RAW_SENSOR products.

## Active Routes

- `SINGLE_FRAME_ZSL`: selects the best warm-buffer candidate.
- `MULTI_FRAME_ZSL`: performs real support-frame processing.
- `YUV_FAST` and `YUV_COMPUTE`: display-referred YUV output.
- `RAW10` and `RAW_SENSOR`: immutable Master RAW16, audited DNG and a separate JPEG ISP clone.

See [Architecture](docs/ARCHITECTURE.md), [Migration](docs/MIGRATION.md) and
[Validation Protocol](docs/VALIDATION_PROTOCOL.md).

## Verification

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Instrumented persistence tests require an Android device or emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```
