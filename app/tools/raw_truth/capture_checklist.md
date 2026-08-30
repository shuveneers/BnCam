# RAW black-truth capture checklist

## A. Covered-lens pair

1. Use the same physical rear camera/lens for both captures.
2. Fully cover the lens so no scene light reaches it.
3. Capture one RAW10 diagnostic shot.
4. Capture one RAW_SENSOR diagnostic shot.
5. Keep the BnCam debug package/log for each shot.
6. Do not enable a new black/bias/color workaround between the two captures.
7. If exposure metadata differ, keep them; do not edit the logs.

Run `analyze_black_truth.py` with `--dark-frame` on both artifacts and compare them with `compare_black_truth.py`.

## B. Decision rule

This checklist intentionally has no numerical pass/fail limit.

- A persistent CFA-specific residual with the lens covered is evidence to inspect the black/bias path further.
- A scene-only green residual is not evidence of an electronic black offset.
- A material RAW10-versus-RAW_SENSOR disagreement points to canonicalization/metadata provenance before color tuning.

## C. Next diagnostic only if the dark frame is clean

Use a neutral gray target and instrument stage-by-stage neutrality through normalized RAW → demosaic → WB → CCM → clamp → GTM → LLF → AgX. That downstream instrumentation is intentionally not part of this delta.
