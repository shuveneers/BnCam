# Microdetail same-RAW qualification — REVERT

Frozen reference: accepted 5×5 chroma Candidate 1. Existing A1–3/D1–3 only; D primary. All three demosaics, two renders each. No captures.

## Stage evidence

DEBUG-only native exports at identical sensor coordinates: direct demosaic, post-noise, exact WB/CCM, pre-LOG, LOG, FLLF, pre-Khronos, Khronos, post-profile, pre-JPEG and JPEG. Spectra occurs before demosaic and is disabled in these fixtures; post-noise means the actual post-demosaic chroma/luma pass. Sharpening is disabled: its stage is explicitly an identity alias, not a claimed filter export.

All 36 diagnostic JPEGs equal the saved frozen Candidate-1 JPEGs byte for byte. All 360 repeated native stage exports are byte-identical. Diagnostic shader bindings were removed after export; production source hashes were restored exactly. Native candidate binaries were separately archived before restoring the source.

ROIs (display x,y,w,h, rotation 180): D1 text 650,1000,512,384; ridges 1500,2000,384,384. D2/3 text 1050,1000,512,384; ridges 2100,1900,384,384. A1–3 detail 1900,1500,384,384; bright 1600,900,384,384. Sensor coordinates and all exact transforms are in work/microdetail/roi-definitions.json and exported model files.

Compare luminance in a common linear working domain; transform camera RGB with exact frame WB×CCM, exponentiate LOG, inverse-sRGB decode encoded stages. Normalized luminance diagnostic sheets are not acceptance images. Coherent-gradient masks and HF energy are proxies, not ground truth or MTF.

| Transition, D text (9 frame/mode cases) | Observation |
|---|---|
| Demosaic → noise | HF −6.90…−3.14%; coherent gradient −0.76…−0.29%. Predominantly grain cleanup; no proof to reduce denoise. |
| WB/CCM → LOG | Identity within floating-point precision in the common domain. |
| LOG → FLLF | Coherent gradient −4.39…−3.41%; fixed glyph-profile contrast −4.06…−2.13%. First systematic glyph-contrast weakening. |
| FLLF → broad-shadow placement | Further gradient/contrast weakening, intentionally separate from the fine-band candidate. |
| Khronos/profile/sharpen, D ROIs | Essentially identity; sharpening disabled. |
| Pre-JPEG → JPEG | HF rises 13.07…16.73%, primarily encoding artifacts; not recovered detail. |

CPU re-evaluation of the exact Gaussian-band math suggests remap attenuation at 8–16 pixels (up to 4.08%). This is not a native reconstructed-pyramid export. Native pyramid readback was prepared but no such evidence was captured.

## One implementation attempt

Candidate bypassed FLLF remapping only for bands at or below 16 pixels. No sharpening boost, NR change, chroma modification or broad-tone changes. Archived patch: work/microdetail/candidate1.patch. Build passed, native same-RAW replay completed 36 renders (18 OLD/NEW cases), all 18 repeated candidate JPEG pairs byte-identical, no demosaic fallback.

| ROI group, all 3 modes × 3 frames | Candidate versus frozen reference |
|---|---|
| D text | Coherent gradient +2.50…+3.79%; glyph contrast +0.74…+4.31%; mean Y +0.012…+0.051%. |
| D ridges | Coherent gradient +0.48…+0.80%; no convincing new visible texture. |
| A detail / bright | Small contrast/brightness changes; no highlight repair. |
| Native 100% acceptance | Slight letter-edge contrast change; insufficient clear fine-structure gain. Scene D is optically soft. |

**REVERT after attempt 1.** Metrics alone do not qualify. No second speculative attempt. Microdetail processing remains frozen, and the source was already restored byte-exactly to the accepted reference before replay. Build of restored source passed. No gate or physical capture was triggered because no same-RAW KEEP exists. Timing is recorded, but unmatched diagnostic baseline timing does not establish a performance delta.

Evidence: work/microdetail/{stage-analysis.json,stage-determinism.json,baseline-identity.json,edge-profiles.json,band-analysis.json,restored-source-checks.json,candidate1/comparison.json,candidate1/checks.json,candidate1/crops/}. The subsequent user request concerning purple highlights and highlight gradients is a separate investigation; it does not reopen fine-chroma or microdetail.

Build isolation correction: restoring source timestamps alone did not invalidate all native shader objects. This was discovered in the subsequent highlight replay; that mixed replay was excluded. All affected translation units/shaders were forcibly rebuilt from the restored source. The isolated highlight replay now produces whole D JPEGs byte-identical to frozen Candidate 1, independently confirming that the rejected FLLF change is absent.
