# Natural RAW highlights — 10 October 2026

Implemented directly in the local repository. This supersedes the earlier bounded scalar highlight estimate. No AE, acquisition, DNG writer, demosaic selection or sharpening changes were made for this correction.

The original sensor-normalized CFA samples retain information that the previous rendering lost. `spectra_raw_finalize.comp` already records four independent source-sample confidences before lens shading, defect correction and exposure mutations. That sideband remains unchanged. The problematic operation was multiplying all four reliabilities in the downstream confidence resolver: a saturated green sample could remove the colour authority of a perfectly measured red sample.

`GradientHighlightRecovery.h` and `highlight_reconstruction.glsl` now decode reliability per RGB channel, including both green sites. Positive cubic interpolation avoids cell boundaries. Aggregate retained evidence is an average; chromatic reliability is the second most reliable RGB channel. Two measured planes can support colour; one measured plane supports structure with conservative neutral colour; complete loss receives a neutral brightness floor consistent with the WB-scaled saturated samples. No neighbouring texture is injected into complete sensor loss.

Recovery uses compatible neighbourhoods at 2, 8, 32 and 128 pixels. It transports low-frequency log channel ratios while keeping the measured guide channel and its gradients. Guide edges and disagreement between available channel ratios reduce transport across colour boundaries. When only one channel remains, colour transport is disabled and the WB-neutral ratio supplies the missing planes. The guide is never reconstructed onto itself. Recovery authority belongs to the missing plane, avoiding a dark rim when the last guide approaches saturation.

This uses the early, gradient-guided and multiscale principles described in [darktable highlight reconstruction](https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/highlight-reconstruction/). It is an independent edge-aware log-ratio reconstruction, not an implementation of darktable's complete Guided Laplacians solver.

The Vulkan route adds mode 13 before the existing colour dispatch. It reuses the colour output buffer as reconstruction scratch, reads immutable demosaic RGB, and has an explicit shader-write/read barrier. CFA orientation travels with the generation-matched confidence map; this is essential for the device's BGGR sensor. Existing physical denoise continues to read its original source; recovery adds a separate channel delta afterwards. Both CPU failure paths use the matching algorithm and immutable neighbour snapshots.

WB, CCM and optional DNG HSM remain scene-linear and signed. LOG/FLLF retains its existing log-luminance pyramids and local exposure owner; it is not used as a new sharpening operation. The stage replay locates the largest remaining loss at the display shoulder, rather than sensor normalization or FLLF. Negative-gamut mapping at the Khronos entrance preserves luminance.

`SoftHighlightTone.h` and the resident tone shader preserve the previous lower-radiance response. The shoulder is C1: the previous curve joins at scene peak 1, transitions to slope 0.12 at 1.1, retains that slope through 2.4, then approaches white asymptotically. Above scene unity, a smooth transition to luminance-based mapping prevents uncertain chroma from driving brightness. The final gamut owner uses continuous grey-axis compression with a shared opponent gain, preserving luminance and linear opponent hue. It does not clamp RGB channels independently. Tone LUT/profile processing remains in its original order.

Validation used the identical physical RAW bytes from `IMG_BNC_20261010_103702_880.dng` and `IMG_BNC_20261010_103715_145.dng` for a frozen pre-change renderer and the corrected renderer. SHA-256 and the complete fixture parameters match for each before/after replay. DNG-derived WB/CCM is held constant. Exact capture Camera2 CCM, LSC and physical noise metadata were not available: these are controlled rendering comparisons, not claims of reproducing the original camera JPEG bit-for-bit.

The instrumented source copies dump post-demosaic, existing denoise/recovery, WB, CCM, highlight characterization, pre-display tone, post-shoulder, pre-gamut and final RGB. Source CFA planes and clipping masks are also saved. Originals remain intact. Measurement scripts and float patches are under `work/highlight-review`; `natural_measurements.json` contains the full results.

The fixed cloud interior uses the same source-only mask for both renders, excludes silhouettes and requires a reliable source red plane. Gradients are measured after 4-pixel block averaging. Gradient gain is least-squares transfer from measured red gradients to JPEG luminance; correlation distinguishes structure from arbitrary added contrast.

| Cloud measurement | Lighter RAW, old → new | Darker RAW, old → new |
| --- | --- | --- |
| Measured red-gradient gain in JPEG | 0.08382 → 0.13270 (+58.3%) | 0.60677 → 0.59967 (-1.2%) |
| JPEG/RAW gradient correlation | 0.78093 → 0.90770 | 0.81447 → 0.81494 |
| JPEG luminance P95–P05 | 0.02181 → 0.03456 (+58.4%) | 0.12160 → 0.12065 (-0.8%) |
| Reliable red change before WB | max 7.45e-9 | max 7.45e-9 |

The darker frame has much less clipping and is effectively preserved. The lighter frame retains more real cloud gradation, with improved correspondence to its measured red structure. Comparison images: `IMG_BNC_20261010_103702_880_natural_comparison.jpg` and `IMG_BNC_20261010_103715_145_natural_comparison.jpg`. Visually inspected cloud boundaries and fully lost cores; the earlier dark core/rim and pink recovery were removed.

Tests passed on the Honor device (Adreno 840):

- Four native regression programs: monotone/C1 shoulder, frozen normal-tone outputs, chroma-independent high-luminance mapping, gradient recovery, full-loss transition, colour-border veto, skin/coloured-light/reflection controls, signed scene transport and CFA level mapping.
- 24 shader combinations: four CFA patterns × three colour/WB matrices × RAW10/RAW_SENSOR fixtures, all clipping masks and confidence ramps. Maximum CPU/Vulkan colour error 7.15e-7, tone error 5.67e-7. Packed source confidence error zero. Existing Spectra/YUV shader outputs remain bit-identical to the frozen baseline.
- Nine full production ISP renders: four CFA patterns in both formats plus complete clipping. Each retains 14 JPEG codes across the synthetic surviving-red cloud ramp. RAW10/RAW_SENSOR JPEGs are exactly identical. The fully lost fixture is spatially uniform, neutral and near white.
- `:app:assembleDebug` succeeded. The APK was installed with `adb install -r` and launched successfully.

Repeat regression commands: `python app/tools/run_default_raw_color_tests.py --sdk <SDK>` and, after `assembleDebug`, `python app/tools/run_default_raw_gradient_pipeline_test.py --sdk <SDK>`.

The lighter RAW has 0.05585% of CFA cells with all four samples at sensor white; the darker RAW has none. Those cores have no recoverable measured cloud texture. They receive plausible neutral transitions, not invented detail. JPEG quantization also limits the visibility of the smallest gradients. Real skin, lamp and reflection captures were not supplied; those controls were tested synthetically rather than described as physical measurements.
