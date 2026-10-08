# Main RAW_SENSOR A–E measurement checkpoint

2026-10-08; baseline `cd319d6f65bfa070bafb4a299f313e717cdb5e82` plus the local opt-in measurement instrumentation described in STATUS.md. This is an intermediate checkpoint, not acceptance of the entire quality phase.

## Verified measurement scope

Five operator-prepared physical scenes, three distinct RAW inputs each. All use actual camera 2, RAW_SENSOR, profile `2_profile_1`, unchanged profile hash `a41f838f8c1671fdda0ff65ca4e0333055e840f8d6decf66314cbbe3e1d629ba`. Every input was replayed through Malvar, AMaZE and Auto Hybrid with frozen production controls; each mode rendered twice, bitexact, without demosaic fallback. All 15 actual MediaStore publications were independently checksum-verified and their decoded stored pixels exactly match the selected Auto Hybrid replay (maximum channel difference zero).

Source, metadata, publication checks, manual ROI metrics, difference maps, and 100%/200% nearest-neighbor crops are in ignored `work/quality/main-rawsensor-{A,B,C,D,E}-physical/`. Compact host scorecards are `work/quality/{A,B,C,D,E}-physical-scorecard.json`. The monitor series and the obstructed RAW10 attempt are excluded from this physical baseline.

## Measurements

Mean absolute RGB disagreement per pixel, encoded sRGB normalized to 0–1; ranges span three independent inputs. These are differences, not error against a reference.

| Scene | ISO | Malvar / AMaZE | Malvar / Hybrid | AMaZE / Hybrid |
| --- | --- | --- | --- | --- |
| A: paper/text/plant/objects | 1767–1919 | .01596–.01681 | .00833–.00942 | .01218–.01401 |
| B: lower light | 2333–2548 | .01438–.01544 | .00843–.00912 | .01279–.01397 |
| C: specular highlight | 1045–1085 | .01524–.01576 | .00926–.00960 | .01126–.01149 |
| D: text/card/woven fabric | 1214–1316 | .01587–.01631 | .01036–.01065 | .01089–.01108 |
| E: colored objects/dotted paper | 318 | .00798–.00828 | .00391–.00588 | .00625–.00765 |

A blank-paper high-frequency R−G spatial standard deviation: Malvar .01185–.01200, AMaZE .00859–.00869, Hybrid .00980–.01147. Lower residual does not prove better detail retention, isolated sensor noise, or a broken noise model. Live ISO/AF/framing vary, so temporal sensor-noise estimates are not justified. B increases ISO at the same approximately 40 ms exposure as A, but the framing also changed.

C1 contains input samples at/above canonical white: R 6198, Gr 5910, Gb 5909, B 3232. **SENSOR_INPUT saturation is proven.** The colored specular boundary in the JPEG remains **UNKNOWN** in origin: the available evidence cannot separate demosaic, color and tone contributions. Low JPEG endpoint clipping does not imply absence of sensor clipping.

D fabric crops show woven structure together with visible grain/chroma variation in all three renditions. Without a low-noise reference and production intermediate readbacks, this does not identify false structure or establish a winner. A's declared edge is measurable in repetition 1; repetitions 2/3 are ambiguous and produce no ringing/width conclusion. These are descriptive profiles, not MTF measurements.

E provides relative color rendition only. The colored household objects have no characterized reflectance, and the white notebook contains printed dots. Neither absolute color accuracy/DeltaE nor a flat-field noise measurement is supported.

## Hybrid evidence and limits

Real RAW crops from A/B/C/D/E were additionally run through the existing isolated GPU numerical harness. Scope is explicitly `ISOLATED_GPU_DEMOSAIC_DIAGNOSTIC_NOT_PRODUCTION_ISP_STAGE`; crop-derived priors and diagnostic context differ from the full production ISP. These results must not be relabeled as its full-frame mask or used to attribute a final JPEG defect to a stage.

E center crop (257×193): weights .323424–.676576; NaN/Inf/out-of-range/unwritten zero; complement error 5.96046e-8; candidate error zero; oracle error 2.98023e-8; three bitexact replays. AMaZE-weight correlations: structure .3005, Nyquist .8265, low signal −.2733, near tie .7671. Noise/chroma-risk fields are constant zero, so correlations are undefined. D's woven-fabric crop has Nyquist/AMaZE-weight correlation .9995. These are model-input associations, not evidence of improved output quality or proven region misclassification.

## Remaining acceptance work

- Main RAW10 comparison with controlled physical inputs; obstructed RAW10 is ineligible.
- Focused actual ultrawide/tele/front screening with verified camera/source metadata.
- Actual light transitions, near/far AF interaction and physical rotations (F/G/H).
- Production stage isolation for noise/color/detail attribution, where bounded safe readbacks can be supported. Current canonical RAW plus final JPEG and separate GPU crops cannot fully separate sensor/demosaic/Spectra/downstream effects.
- Final ranked tuning roadmap after remaining evidence. No threshold, weight, WB, color, tone, denoise, exposure or AF policy changes were made. No product correctness bug is established by A–E.

The existing full device gate and thermally gated warm benchmark passed after app instrumentation. Their numerical/accounting results are in STATUS.md. Diagnostic capture durations are not performance rankings.
