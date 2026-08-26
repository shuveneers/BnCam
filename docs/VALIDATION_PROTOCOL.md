# Imaging Validation Protocol

## Reproducibility

Use a fixed lens/profile, clean lens, tripod where specified and unchanged illumination. Capture
at least five repetitions per route: YUV_FAST, YUV_COMPUTE, RAW10 single/multi and RAW_SENSOR
single/multi. Preserve the shot-debug directory with every JPEG/DNG pair.

Every RAW capture must show a complete `RawDomainContract`, selected metadata sources, BL/WL,
sample min/p1/p50/p99/max, clipping, lens-shading state, provenance, performance report and
`DNG_AUDIT:status=PASS`. Any master-integrity failure is a release blocker.

## Scenes And Metrics

| Scene | Setup | Required checks |
|---|---|---|
| Dark frame | Lens fully capped; base and high ISO | CFA black residual, hot pixels, row/column pattern, clipping |
| Flat field | Uniform diffuse illumination at two focus distances | Per-channel flat-field residual and lens-shading stability |
| Gray card | 18% card filling center and frame | neutral RGB ratios, exposure repeatability, WB metadata |
| ColorChecker | Controlled D50/D65 light | median/p95 Delta E 2000, CCM and illuminant provenance |
| Resolution chart | Tripod, base ISO, center/corners | MTF50/edge sharpness, halos, false color |
| HDR scene | Bright emissive target plus deep shadows | channel clipping, highlight rolloff, no global crushing |
| Low light | Static scene at several ISO levels | luma/chroma noise, retained detail, false color |
| Motion | Moving subject plus static background | selected-frame motion score, ghost/reject provenance |
| Repeated scene | Ten unchanged captures | exposure/WB variance, latency and output consistency |

## Acceptance Gates

- DNG audit passes and payload samples do not materially exceed the declared WhiteLevel.
- Debug master CRC remains unchanged after JPEG processing.
- Dark-frame median residual is within 0.5% of native range per CFA plane after subtraction.
- Corrected flat-field coefficient of variation is at most 5%, with no new corner color cast.
- Gray-card channel ratios are within 3% after WB; repeated exposure variation is at most 0.15 EV.
- ColorChecker median Delta E 2000 target is 8 or lower and p95 is 15 or lower when a calibrated
  reference workflow is available.
- Against a versioned reference, SSIM/MS-SSIM and PSNR may not regress beyond measurement noise;
  investigate a PSNR loss over 0.5 dB or SSIM loss over 0.005.
- MTF50 must not regress relative to baseline and sharpening must not introduce visible halos.
- Multi-frame output must report more than one accepted frame; otherwise it is classified as
  anchor-only and cannot be presented as a successful computational merge.
- No route may exceed its adaptive buffer capacity or report unresolved JNI/native failures.

## Tooling Notes

Metrics may be calculated with any reproducible toolchain. Record tool name/version, input files,
crop coordinates, color space and command/configuration beside the results. Never compare JPEG
and DNG code values directly without a documented RAW development transform.
