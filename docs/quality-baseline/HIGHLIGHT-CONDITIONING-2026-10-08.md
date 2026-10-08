# Highlight confidence + luminance conditioning — REVERT — 2026-10-08

OLD is the frozen accepted Candidate 1 fine-chroma baseline. This is one new implementation attempt, independent of the two previously rejected highlight experiments. All development used the immutable existing RAW10 A1–3/D1–3 fixtures; no new physical captures and no experimental APK installation.

Attempt 1 implemented the requested chain on the native Vulkan route: pre-finalize per-sensel CFA confidence, post-WB/CCM luminance-preserving chroma fade, scalar pre-LOG conditioning, and RAW-confidence protection of actual FLLF bands. Confidence only changes sideband metadata, never mosaic pixels. Four physical-position confidences are UNORM8-packed into the existing cell sideband word; all four CFA positions participate symmetrically, independent of Bayer order. Original per-CFA black/white normalization remains the authority. Confidence uses quintic smootherstep between normalized source .94 and 1.0, then a positive-weight cubic B-spline interpolates the cell confidence to full resolution. No donor, channel ratio, reconstruction, RGB blur, or demosaic fallback.

Chroma handling remains Y + q C. The scalar curve is identity for L <= .8, otherwise f(L)=.8+.6 asinh((L-.8)/.6), blended by 1-q and applied as one RGB scale. At the join f=.8, f'=1, f''=0 on both sides; the positive derivative above the join is 1/sqrt(1+t²), second derivative -t/(.6*(1+t²)^1.5). This verifies actual C2 continuity; spatial monotonicity of the confidence-gated mapping was measured separately rather than inferred from that curve. Above-unity headroom remains unbounded. FLLF remapped coefficients blend back to original coefficients with the same generation-validated mask, and the full-resolution accumulated correction is gated as well. The general FLLF curve and Khronos code remain unchanged.

The unchanged production demosaic/fine-chroma/luma functions were verified against frozen sources. The temporary prototype only qualified the GPU route: every actual replay used its requested demosaic mode without CPU fallback. CPU fallback parity was not implemented/qualified for a visually rejected candidate. Debug exporters were temporary and removed during restore.

## Actual stage result

The OLD boundary-normal profiles first develop the large luminance valley in the Khronos output, while the preceding scene-linear profiles remain increasing apart from small local texture/noise. This happens when unreliable post-CCM clipped colour enters the unchanged mapper. The new confidence fade removes the magenta and nearly removes this valley. It does not provide enough visible core gradient.

For A1 Hybrid, physical clipped-core pre-LOG Y spans approximately 1.071–1.337 after conditioning. Post-FLLF remains 1.071–1.337: highlight protection releases the local remap as intended. Khronos compresses this to approximately .877–.926. Across all A/modes the final JPEG clipped-core encoded-Y standard deviation is only .00295–.00404, approximately .75–1.03 code values. The actual 100% JPEG therefore still looks like a flat white blob. The grey Y diagnostic is not an acceptance rendering and was not separately contrast-normalized.

The 36 fixed RAW-boundary normal profiles record raw peak, q, RGB, Y, chroma magnitude, first and second Y derivatives, and toward-core drawdown. OLD maximum Khronos-output drawdown is .28476; NEW is .00830. Median drawdown falls from .23420 to .000772. The remaining small changes include existing local texture/noise; this is not a claim that every sampled derivative is strictly monotonic. Eliminating the old valley is insufficient for KEEP because the white core remains visibly flat.

No second implementation attempt was used. The exports do not demonstrate a specific wrong placement or scale with a proven limited correction that would satisfy the visual criterion. A speculative curve retune or global Khronos rewrite is not justified by this rejected result.

## Result matrix

| Check | OLD | Attempt 1 NEW |
|---|---:|---:|
| A1–3 × 3 routes magenta ROI pixels | 36,096 | 0 |
| Worst boundary-profile Khronos Y drawdown | .28476 | .00830 |
| Actual core appearance | Purple clipped area | Flat white area: FAIL |
| Chroma-only maximum linear-Y error | — | 2.26e-7 |
| ROI pre-JPEG 255 components | 0 | 0 |
| ROI final float components >1 | 0 | 0 |
| A valid coloured-flower maximum delta | — | 0 codes |
| D whole-JPEG identity | reference | 9/9 mode/frame pairs |
| JPEG deterministic repeat pairs | reference | 18/18 |
| Debug/production JPEG identity | reference | 18/18 |
| Repeated stage/byte export identity | reference | 432/432; float exports finite |
| Requested demosaic without fallback | reference | 36/36 renders |
| Exploratory native pipeline time, Hybrid/Malvar/AMaZE | restored run | +1.91% / +1.74% / +2.10% |

Timing uses consecutive production A1/D1 round-1 samples, two samples per mode, all thermal status 0. It is exploratory, not a randomized performance gate; no qualified performance claim. No full devicegate was run after a visual FAIL.

## Exact crops and exports

![A1 Hybrid 100% OLD/NEW and diagnostics](../../work/highlight-conditioning/result.png)

- A1 Hybrid: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A1/Hybrid/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A1/Hybrid/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A1/Hybrid/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A1/Hybrid/profile-0.png).
- A1 Malvar: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A1/Malvar/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A1/Malvar/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A1/Malvar/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A1/Malvar/profile-0.png).
- A1 AMaZE: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A1/AMaZE/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A1/AMaZE/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A1/AMaZE/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A1/AMaZE/profile-0.png).
- A2 Hybrid: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A2/Hybrid/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A2/Hybrid/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A2/Hybrid/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A2/Hybrid/profile-0.png).
- A2 Malvar: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A2/Malvar/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A2/Malvar/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A2/Malvar/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A2/Malvar/profile-0.png).
- A2 AMaZE: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A2/AMaZE/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A2/AMaZE/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A2/AMaZE/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A2/AMaZE/profile-0.png).
- A3 Hybrid: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A3/Hybrid/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A3/Hybrid/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A3/Hybrid/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A3/Hybrid/profile-0.png).
- A3 Malvar: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A3/Malvar/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A3/Malvar/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A3/Malvar/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A3/Malvar/profile-0.png).
- A3 AMaZE: [100% OLD/NEW](../../work/highlight-conditioning/attempt1/crops/A3/AMaZE/old-new.png), [four RAW CFA confidence maps](../../work/highlight-conditioning/attempt1/crops/A3/AMaZE/raw-confidence.png), [native confidence + stage Y](../../work/highlight-conditioning/attempt1/crops/A3/AMaZE/stages.png), [fixed boundary profiles](../../work/highlight-conditioning/attempt1/crops/A3/AMaZE/profile-0.png).

Complete machine-readable evidence: [profiles incl. RGB/chroma/dY/d2Y](../../work/highlight-conditioning/profiles.json), [stage numerics](../../work/highlight-conditioning/stage-numerics.json), [replay audit](../../work/highlight-conditioning/replay-audit.json), [C2/frozen contracts](../../work/highlight-conditioning/numerical-contracts.json), [timings](../../work/highlight-conditioning/performance.json). Native post-LOG, pre-LOG, post-FLLF, Khronos input/output float ROIs for both repeats are retained in `work/highlight-conditioning/attempt1-debug/{A1..3,D1..3}` with fixed sensor/display coordinates from `work/microdetail/roi-definitions.json`.

Restoration: every touched source restored byte-for-byte from the session's Candidate 1 snapshot. Forced current timestamps recompiled all affected units; assembleDebug PASS. Restored A1/D1, all three modes, two repeats: 12/12 JPEGs byte-identical to Candidate 1. Installed Honor APK SHA256 remains `6adb64b800a9d3024586fb6ab43fe0b5863468a0ec9defad96d57ee352d6dba4`, exactly the previously restored accepted baseline; neither this prototype nor its debug build was installed. The phone remains on Candidate 1. No captures were taken.

REVERT
