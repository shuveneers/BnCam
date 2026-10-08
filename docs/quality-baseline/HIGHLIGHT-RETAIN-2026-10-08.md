# KEEP A — independent highlight chroma improvement, 2026-10-08

Variant A is accepted independently of the remaining flat luminance centre. It visibly removes the false magenta in A1–A3 across Hybrid, Malvar and AMaZE without pink islands or a new coloured rim. B and C pass the chroma checks too, but their small profile-metric changes do not justify additional tone authorities for this goal. Production and the installed Honor build retain A only.

The source RAW10 fixtures and replay metadata are unchanged. OLD is the frozen accepted Candidate1, including the 5×5 covariance-aware fine-chroma estimator. A and B each have 36 fresh production renders (A1–3/D1–3 × three modes × two repeats); C reuses the preserved 36 production renders and matching debug data from highlight-conditioning attempt1. All three comparisons use original Khronos. No physical captures were taken.

| Variant | Magenta pixels, 9 A ROIs | Worst Y valley | Worst rim | Valid-colour maximum delta | D whole JPEG exact | Repeat exact |
|---|---:|---:|---:|---:|---:|---:|
| OLD Candidate1 | 36,096 | .28476 | .28476 | reference | reference | reference |
| A | 0 | 0.00831993 | 0.00107966 | 0 codes | 9/9 | 18/18 |
| B | 0 | 0.00829018 | 0.00101765 | 0 codes | 9/9 | 18/18 |
| C | 0 | 0.00829657 | 0.00089570 | 0 codes | 9/9 | 18/18 |

The magenta count is over identical display ROI [1600,900,384,384], using min(R,B)−G > .08 and max(R,G,B) > .8 in the actual JPEG. Valley is maximum accumulated Y drawdown toward the core; rim is maximum Y above both profile endpoints. Both use the same fixed RAW-boundary normal profiles and actual native Khronos output, linear Rec.709 luminance. Existing texture/noise can still produce small local drawdowns. Valid colour is the identical flower ROI [3200,600,384,384]; its zero delta is a fixture control, not a general colour-accuracy calibration. All nine A ROIs have finite native stages, zero pre-JPEG 255 components and zero post-profile components above one. The visually flat centre persists despite the lack of final numeric clipping.

Implementation: capture per-CFA physical source confidence before finalize exposure/lens changes, quintic rolloff .94–1.0, UNORM8 packed in the existing sideband. Aggregate all four CFA confidences and interpolate confidence with positive cubic B-spline weights at full resolution. The existing post-WB/CCM linear chroma fade uses this q directly, Y+q(RGB−Y). There is no RGB spatial blur, channel reconstruction, new exposure authority or subsampled output. CPU fallback mirrors this policy. The GPU oracle verifies confidence exactly and RGB within 7.16e-7 across 24 CFA/lens/RAW-format cases.

The final production diff against the accepted local snapshot is confined to HighlightGamutProtectionV2.h, the two confidence/colour shaders, and an IspCore diagnostic policy string. The test is updated for packed confidence and compares legacy branches against the saved local baseline, not an unrelated Git HEAD. Fine-chroma/luma sources are byte-identical; Malvar, AMaZE and Auto Hybrid function bodies are exact; the tone shader, tone backends, runtime and native replay harness are byte-identical to the frozen snapshot. LOG → FLLF → KHRONOS remains original. All debug exporters and the luminance prototype are absent from production.

Validation: assembleDebug PASS; Honor Vulkan CPU/GPU oracle ALL PASSED, including exact Camera2 unequal greens, intact colour below the confidence shoulder, signed/headroom colour and original legacy RAW/tone/YUV branches. Final minimal build A1/D1 × three modes × two repeats is byte-identical to the compared variant A, 12/12. A/B debug exports match production JPEGs 9/9 each and repeat 216/216 stage pairs each; the wide field exports match A JPEGs 9/9 and repeat 108/108 pairs. All float exports are finite. Inherited debug stage label `wb-ccm` actually duplicates the final colour dispatch, so it is not used as an independent pre-fade luminance measurement; `chroma-safe` and the tone stages are valid. The actual A chroma-safe stage compared with the frozen native pre-LOG stage preserves scene-linear Y within 2.26e-7 across all nine A frame/mode pairs.

Performance: q adds sixteen bounded confidence reads in the existing colour dispatch and four sample-confidence evaluations in finalize; it adds no image buffer, GPU dispatch or tone pass. The final A1/D1 round-1 replay timings are recorded below. These small, sequential warm-up samples are exploratory, not a randomized latency regression gate; there is no claim of a precisely qualified overhead percentage.

| Frame | Hybrid pipeline ms | Malvar pipeline ms | AMaZE pipeline ms |
|---|---:|---:|---:|
| A1 | 2943.15 | 2907.30 | 2934.15 |
| D1 | 2768.15 | 2742.26 | 2769.14 |

Installed debug APK verified on Honor: `d8e841ae97c0fd76a60d8e892bf5dada3e50c229b494eb0b81c16d70347b7b74`.

![Identical 100% OLD/A/B/C, A1 Hybrid](../../work/highlight-retain/ABC-100pct.png)

Actual 100% JPEG crop coordinates are [1450,950,500,350], without resampling or gain. Separate OLD/NEW comparisons:

| Frame/mode | A | B | C |
|---|---|---|---|
| A1 Hybrid | [A](../../work/highlight-retain/A-analysis/crops/A1/Hybrid/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A1/Hybrid/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A1/Hybrid/old-new.png) |
| A1 Malvar | [A](../../work/highlight-retain/A-analysis/crops/A1/Malvar/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A1/Malvar/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A1/Malvar/old-new.png) |
| A1 AMaZE | [A](../../work/highlight-retain/A-analysis/crops/A1/AMaZE/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A1/AMaZE/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A1/AMaZE/old-new.png) |
| A2 Hybrid | [A](../../work/highlight-retain/A-analysis/crops/A2/Hybrid/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A2/Hybrid/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A2/Hybrid/old-new.png) |
| A2 Malvar | [A](../../work/highlight-retain/A-analysis/crops/A2/Malvar/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A2/Malvar/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A2/Malvar/old-new.png) |
| A2 AMaZE | [A](../../work/highlight-retain/A-analysis/crops/A2/AMaZE/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A2/AMaZE/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A2/AMaZE/old-new.png) |
| A3 Hybrid | [A](../../work/highlight-retain/A-analysis/crops/A3/Hybrid/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A3/Hybrid/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A3/Hybrid/old-new.png) |
| A3 Malvar | [A](../../work/highlight-retain/A-analysis/crops/A3/Malvar/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A3/Malvar/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A3/Malvar/old-new.png) |
| A3 AMaZE | [A](../../work/highlight-retain/A-analysis/crops/A3/AMaZE/old-new.png) | [B](../../work/highlight-retain/B-analysis/crops/A3/AMaZE/old-new.png) | [C](../../work/highlight-retain/C-analysis/crops/A3/AMaZE/old-new.png) |

Evidence: [matrix](../../work/highlight-retain/ABC-matrix.json), [final source and replay audit](../../work/highlight-retain/final-audit.json), [device oracle](../../work/highlight-retain/device-tests.log), [build](../../work/highlight-retain/final-build.log), [separate luminance result](LUMINANCE-FIELD-2026-10-08.md). Earlier combined REVERT reports are historical: their successful chroma component is now independently recovered and accepted as A.
