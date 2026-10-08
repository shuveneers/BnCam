# Quality baseline — ongoing, 2026-10-08

Baseline: `cd319d6f65bfa070bafb4a299f313e717cdb5e82`; local measurement instrumentation is uncommitted. No quality tuning, reset, commit or push performed.

## Proven delta

| Item | Evidence / status |
| --- | --- |
| Existing full device gate before quality work | PASS: `work/quality-baseline-gate-rerun.log` |
| Full gate after adding opt-in replay instrumentation | PASS: `work/quality-instrumentation-gate.log` |
| Targeted JVM / connected | 56 / 9 passed; includes native noise provenance and Vulkan/physical chroma/luma |
| Native geometry / CFA suite | 80 cases passed |
| Real held queue accounting | 100 = 97 REJECTED + 3 CANCELLED; no lost terminal outcomes |
| Live shutter accounting | 100 = 8 PUBLISHED + 92 REJECTED; 8 publication evidence rows independently checked |
| Existing fixture Hybrid readback | 257×193, three bitexact replays; weight min 0.0517537, max 0.948246; NaN/Inf/out-of-range/unwritten = 0; complement error 8.56817e-8; candidate error 0; oracle error 1.88356e-9 |
| Persistent warm benchmark | Ten measured samples per algorithm after three warmups; thermal status 0 before/after every measured sample; bitexact and qualified for all modes |
| New measurement analyzer | Eight synthetic correctness tests passed; clipping, odd-origin RAW CFA ROI ordering, valid/ambiguous edge handling, identity rejection, nearest crops, neutral residual and Hybrid oracle |
| New capture/replay collector | Physical A/B/C/D/E: three independent RAW_SENSOR captures each (15 total), three actual algorithms without fallback, two bitexact replays per algorithm |
| Actual published output versus replay | All 15 A–E publications independently checksum-verified and decoded pixels identical to the selected Hybrid replay (max channel difference 0) |

Latest benchmark total median / p90 (ms): Malvar 3792.16 / 3811.073; AMaZE 3824.915 / 3840.803; Hybrid 3853.605 / 3868.411. This run had the display off after thermal cooldown. Earlier display state was not standardized, so the timing difference from prior runs is not evidence of a product performance regression. The standalone benchmark uses its documented fixture/default config and does not substitute for frozen production-profile quality replay.

## Reproducible fixes / encountered failures

1. **Measurement export infrastructure:** the first gate wrote runtime dumpsys output into tracked qualification docs, causing `git diff --check` to fail on trailing whitespace after all device work passed. Runtime exports now default to ignored `work/single-frame-qualification`; the gate reads its benchmark summary there. The failed run's generated data was preserved under `work/quality-baseline-generated-docs`; only files overwritten by that run were restored to their original baseline contents. Full rerun passed.
2. **New analyzer correctness:** exact 10%/90% edge-level hits initially counted two crossings. A synthetic ramp reproduced it; half-open crossing intervals fixed it and the regression test passes. Hybrid complement/oracle calculations use float64 to retain the actual float32 residuals.
3. **Controlled scene A interrupted:** ADB temporarily disappeared while polling terminal records. On reconnect, the terminal record proved `pipeline_not_ready:NO_ACTIVE_PIPELINE`, expected YUV, active `2_disabled` profile. No quality export directory existed. The phone was still running (uptime >134000 seconds), so no device reboot was observed. The transport interruption's underlying cause is unproven. This attempt is not counted as RAW/scene quality evidence. Both debug measurement flags were explicitly cleared after reconnection.

No product correctness bug has been established in this phase.

Resume evidence: the first new series was RAW10 and visibly obstructed/defocused, so it is excluded from controlled-scene quality claims. The next three RAW_SENSOR monitor photographs are a separately limited text/subpixel diagnostic, not a substitute for physical A. After physical setup confirmation, A/B/C/D/E were captured successfully. The collector now refuses an unexpected source/camera or a changed camera/profile within a repeat series. There was no RAW10-versus-RAW_SENSOR provenance mismatch in the obstructed series: recipe, manifest and terminal context all consistently reported RAW10.

## Controlled quality coverage

| Scene | Main RAW_SENSOR | Main RAW10 | Ultrawide | Tele | Front |
| --- | --- | --- | --- | --- | --- |
| A | Three physical indoor captures + three-mode frozen replays; manual ROI analysis | Pending | Pending | Pending | Pending |
| B | Three lower-light captures + three-mode frozen replays; manual ROI analysis | Pending | Pending | Pending | Pending |
| C | Three specular-highlight captures + three-mode frozen replays; sensor saturation proven | Targeted if needed | Targeted if needed | Targeted if needed | Targeted if needed |
| D | Three packaging/card/fabric captures + three-mode frozen replays; manual ROI analysis | Pending | Pending | Pending | Pending |
| E | Three original captures plus three source-comparison captures; manual ROI analysis | Three captures with replays/publication checks; framing/ISO/AF limit format attribution | Targeted if indicated | Targeted if indicated | Targeted if indicated |
| F | Pending actual lighting transition | Targeted if indicated | Targeted if indicated | Targeted if indicated | Targeted if indicated |
| G | Pending actual near/far focus sequence | Targeted if indicated | Targeted if indicated | Targeted if indicated | Targeted if indicated |
| H | Pending actual physical rotations | Targeted if indicated | Targeted if indicated | Targeted if indicated | Targeted if indicated |

The existing old native fixture remains uncontrolled and is not relabeled as A–H. Controlled physical measurements and scorecards live under `work/quality/`; numerical differences alone do not establish a quality winner. No lens-specific finding or absolute color/noise score is claimed.

## Initial measured findings (not a tuning decision)

- A: ISO 1767–1919 at 39,999,967 ns; B: ISO 2333–2548 at the same exposure. The profile hash is identical within each three-frame series. AF and ISO vary between live captures; there is framing movement, so these are not registered temporal-noise trials.
- A frozen JPEG difference mean (mean absolute RGB per pixel, encoded 0–1): Malvar/AMaZE 0.01596–0.01681; Malvar/Hybrid 0.00833–0.00942; AMaZE/Hybrid 0.01218–0.01401. These establish disagreement, not which rendition is correct.
- Corrected blank-paper A ROI: high-frequency R−G spatial std is 0.01185–0.01200 for Malvar, 0.00859–0.00869 for AMaZE, 0.00980–0.01147 for Hybrid. The paper is an uncharacterized neutral proxy and the metric includes optics, scene, encoding and processing. Lower chroma residual does not by itself establish retained detail or a noise-model bug.
- C1 canonical RAW has 6198 R / 5910 Gr / 5909 Gb / 3232 B samples at or above white. Sensor input saturation is established. A colored highlight boundary is visible in the JPEG; its exact pipeline origin remains UNKNOWN. No recovery/threshold/color change was made.
- Isolated GPU diagnostics on actual source-frame crops are finite, in range and bitexact across three replays. A paper crop oracle max 1.67e-8; C highlight crop oracle max 1.03e-7. These crops use the diagnostic harness's priors/context and do not prove the production full-frame Hybrid mask distribution.
- Scene captures were thermally recorded (A status 1 before/after). Their diagnostic-render durations are not treated as a thermally qualified performance ranking; the separate warm gate above was status 0.

## Remaining measurement limits

- Frozen JPEG replays and canonical RAW export are implemented; direct GPU crop diagnostics are separately scoped. Full production resident readbacks after demosaic, WB/color and tone remain unavailable. Artifact origin stays UNKNOWN without adequate independent evidence.
- Flat/neutral/texture/edge ROIs require manual declaration on actual images. Positional center crops carry no semantic classification.
- A single spatial RAW/ROI variance does not separate shot/read/fixed-pattern noise. Repeats require stable scene/framing and metadata comparison.
- Absolute color accuracy needs a characterized reference and illuminant. Colored household objects support reproducible relative comparison only.
- No scene-dependent or lens-dependent tuning proposal is made before controlled measurements. The qualified architecture and current settings remain unchanged.

## Completed E delta

E: three distinct RAW_SENSOR inputs on camera 2, unchanged profile hash, ISO 318 each. All 18 algorithm renders are bitexact within their replay pairs; all three actual publications match the selected replay pixels. Malvar/AMaZE mean absolute encoded RGB disagreement is 0.007982–0.008280. Red/blue/green/yellow household objects and dotted white paper support relative rendition measurements only, not absolute color accuracy or a flat-field noise measurement.

E isolated center crop: weights 0.323424–0.676576; NaN/Inf/out-of-range/unwritten 0; complement error 5.96046e-8; candidate error 0; oracle error 2.98023e-8; three replays bitexact. This is the separate GPU harness, not a production full-frame mask. Noise and chroma-risk diagnostic fields are constant zero here, so their correlations are undefined and cannot establish production noise adaptation.
