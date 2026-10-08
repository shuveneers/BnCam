# REVERT luminance-field prototype; KEEP chroma A — 2026-10-08

The remaining pale, flat highlight centre is a separate unresolved luminance problem. The bounded smooth-field prototype does not establish a reliable reconstruction on A1–A3. It is rejected without removing the accepted confidence/chroma correction A. This is not a successful highlight-gradient fix.

The prototype uses the same immutable RAW10 files, per-CFA black/white calibration and a wider native scene-linear export. Display ROI [1350,800,768,640] corresponds to sensor ROI [1978,1632,768,640] and contains the complete primary saturated component and its closed boundary. Exports match A production JPEGs exactly, 9/9; all 108 repeated stage pairs are exact and finite. No new capture was made.

A low-frequency red field is formed by four positive binomial 5×5 passes on source CFA cells. A single component-wide affine fit Y=aR+b uses reliable unsaturated boundary samples (source peak .60–.94, with a two-cell guard around clipping and a 22-cell outer ring). Its target is actual native scene-linear luminance from the accepted A chroma-safe stage, averaged over source cells. There is no local cell channel ratio, green/blue donor reconstruction, artificial radial gradient or global tone mapping. Eight held-out angular boundary sectors and a lower .82 peak ceiling test extrapolation stability. Correction is bounded by measured red-intensity support and by the existing confidence loss; unvalidated source intensities cause abstention. Above-unity scene-linear headroom is retained.

| RAW | Reliable boundary smoothed R | Clipped-core smoothed R | Unsupported core | Worst sector Y RMSE | Lower-ceiling holdout Y RMSE |
|---|---|---|---:|---:|---:|
| A1 | 0.26069–0.39397 | 0.43212–0.83463 | 100% | 0.05176 | 0.02820–0.02878 |
| A2 | 0.26013–0.40755 | 0.43371–0.72292 | 100% | 0.04489 | 0.02256–0.02282 |
| A3 | 0.25911–0.41151 | 0.43541–0.70103 | 100% | 0.04488 | 0.02208–0.02234 |

All nine frame/mode fits put every clipped-core cell outside the measured reliable boundary red range. The bounded prototype therefore changes exactly zero core cells. A stronger field would require extrapolating a colour/luminance relationship that these data have not validated. Sector errors and the negative bias in near-ceiling validation further weaken that assumption. A smooth-looking extrapolated field is insufficient evidence of correct scene luminance.

The synthetic identifiability check also constructs two smooth underlying fields with identical measured unsaturated R, identical clipped G/B and identical reliable boundary, yet differing unobserved linear Y by up to .28608. This illustrates ambiguity; it is not a recovered scene or evidence that red carries no spatial information. The conclusion is limited to this prototype and these observations: reliable luminance reconstruction has not been established without an extra unvalidated chromaticity assumption or additional information.

This prototype runs on saved RAW/native arrays on the host. Its field and support maps are diagnostics, not actual reconstructed acceptance JPEGs. It was never added to the Android image pipeline or installed. The final Android build contains A only, with original FLLF and Khronos. The remaining core has a visible flat appearance even though measured final stages contain no output clipping at one. Eliminating magenta and its old luminance valley is independently successful and retained.

Evidence: [model and validation](../../work/highlight-retain/luminance-field.json), [prototype](../../work/highlight-retain/luminance-field.py), [support review](../../work/highlight-retain/review-field.py), [A1 diagnostic fields](../../work/highlight-retain/luminance-field/A1/diagnostics.png), [accepted chroma report](HIGHLIGHT-RETAIN-2026-10-08.md). The field panels use fixed diagnostic scales; acceptance is based on the unchanged actual JPEG crops in the chroma report.
