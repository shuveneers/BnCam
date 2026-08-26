# Pipeline Migration

## Removed Routes

- Direct `Raw10Merger` and `RawSensorMerger` JPEG paths.
- Legacy `RawToneMapper` and its independent highlight/exposure stack.
- Byte-array `YuvMerger`; active YUV uses the HardwareBuffer pipeline in `native-lib.cpp`.
- Unused OpenCV `ImageAnalyzer` and obsolete RAW10 OpenGL debug result models.
- Unimplemented capture modes: dual-band gain, cross-sensor fusion, action, long-exposure night
  and RAW-as-a-mode. RAW10/RAW_SENSOR are frame sources under the two implemented ZSL modes.
- Legacy libpatcher controls without a runtime effect.

Existing imported profiles with a removed capture-mode name do not gain a hidden substitute.
They retain or fall back to the supported single-frame default through normal profile parsing.

## Current Mapping

| Legacy concern | Current owner |
|---|---|
| Loose BL/WL values | `RawDomainContract` |
| RAW10 unpack | `DngMerger` plus `RawSampleReaders` tests |
| RAW_SENSOR stride read | `DngMerger` plus `RawSampleReaders` tests |
| RAW output | immutable `MasterRawFrame` |
| DNG correctness | `DngWriter`, `DngSemanticRules`, `DngSemanticAuditor` |
| RAW JPEG | `IspCore` JPEG-only clone |
| YUV single/multi | `YUV_FAST` / `YUV_COMPUTE` |
| Metering/manual exposure | `CameraControlPolicy` |
| Buffer sizing | `CaptureBufferBudget` |

No compatibility layer rescales black levels by appearance, guesses exposure time or mutates
RAW data to make a DNG resemble the JPEG.
