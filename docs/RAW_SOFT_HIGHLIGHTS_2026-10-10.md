# Soft RAW highlights — 2026-10-10

Inspected the latest two RAW/JPEG pairs pulled from DCIM/BnCam without modifying the originals:

- IMG_BNC_20261010_101742_733: lighter pair, identified by the user as BnC AE; DNG ISO 100, shutter 4.267583 ms.
- IMG_BNC_20261010_101752_543: darker pair, identified by the user as Standard AE; DNG ISO 100, shutter 1.348218 ms.

The brighter JPEG has visibly abrupt near-white cloud patches. Half-resolution CFA-cell measurements, aligned using DNG orientation 6, show about 0.106% of brighter-frame RGB cells with all reconstructed channels >=0.985 of normalized white. About 0.914% of its resized JPEG pixels have every channel >=250. Most of these JPEG-white pixels retain a measured red channel while green is saturated; some also retain blue. The darker RAW has essentially no all-channel saturation in the upper third. These are CFA-cell diagnostic measurements, not a demosaiced saturation oracle or a controlled AE comparison; framing differs between the two captures.

## Changes

Single-shot RAW JPEG rendering now uses a wider scene-linear shoulder starting at 0.60 instead of 0.76. Offset preparation starts at 0.44, leaving lower midtones and shadows unchanged. The curve remains monotonic and approaches white without a unit clamp. Both RAW preview kernels use the corresponding wider shoulder. Shared Spectra/merged RAW and YUV tone arithmetic retains its original mapper.

Single-shot capture additionally estimates bounded neutral-highlight radiance from surviving demosaiced sensor channels, gated by the generation-matched source-Bayer clipping confidence. Channel contribution fades smoothly from normalized sensor 0.90 to 0.98; recovery fades out if too little channel support survives or the highlight is insufficiently bright. A scalar gain, bounded to 1.75, transports that estimate through the signed Camera2 color transform before the existing clipping-color confidence mix. It does not alter saved Bayer data, white balance, the camera matrix, acquisition exposure or infer missing cloud texture. Fully saturated channels provide no recovery authority. This is an estimate under a neutral-highlight assumption, not an exact radiance measurement.

Native CPU fallback paths implement the same helper. The source clipping confidence and existing color neutralization remain separate from the display shoulder. No global spatial blur or new sharpening change was required; the accepted softer rendering is concentrated in highlights.

## Verification

- Debug APK Kotlin/native/shader build succeeded; installed on Honor BKQ-N49 AUWE025B03006422 and restarted.
- Android native SoftHighlightToneTest passes: low-range identity, ordered highlight ramp, 26 distinct sRGB8 display codes across the tested 0.8–2.0 input range, threshold continuity, channel order, surviving-channel radiance ramp, trusted-data identity and fully saturated no-recovery behavior.
- Production Vulkan color tests pass for 24 combinations of CFA pattern, simulated lens transform and RAW10/RAW_SENSOR. Maximum CPU/GPU color error 7.15e-7; tone error 2.98e-7. Exact unequal green gains remain preserved. Shared Spectra/YUV paths pass bit identity against the test's frozen accepted revision.
- Both original RAWs were replayed through the production native GPU renderer before and after the repair. DNG-derived color transforms were used, without the original capture's exact Camera2 CCM, LSC or noise metadata. These replays verify behavior on the captured Bayer data but are not exact re-developments of the original JPEGs. The lighter replay's high-end tonal steps are less compressed and cloud transitions are softer; flat regions remain visible. Saved measurement files and comparisons must not be presented as proof that every clipping patch is gone.
- `git diff --check` passes. The unrelated broad Kotlin suite remains non-green as documented in the earlier AE recovery note; it was not rerun for this native rendering change.

Evidence and preserved inputs: work/highlight-review. Build: build.log; GPU tests: color-test.log; final replays: *_final.jpg and *_final.txt; initial RAW diagnostics: raw_clipping.json. Original captures are the unsuffixed DNG/JPEG files.

## Limits

No rendering curve can recover fully saturated source detail. Even partial-channel estimates cannot guarantee the original cloud structure when only one channel survives. The repair improves transitions and uses available signal; it does not guarantee zero flat areas in existing or future exposures. Avoiding source clipping altogether would require acquisition changes or additional exposure data, which this repair does not introduce. Standard Android AE acquisition remains untouched. Preview uses the wider shoulder; surviving-channel recovery is currently in the single-shot JPEG color stage. Extreme explicit user curves/white controls can still intentionally flatten display highlights.
