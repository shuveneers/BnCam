# SPECTRA Neural — Phase 3 completion audit

**Baseline:** GitHub `34798c30d9364a4aecc0912e2c0110f2b9b34d25` + virtual N006R + frozen N007A–N007E + frozen N008A–N008F.

**Phase:** 3 — Student + distillation + export contract.

## Exit state

Phase 3 establishes a complete offline path:

`governed RAW dataset → frozen Teacher → mobile Student distillation → Student checkpoint → deterministic FP16 export → manifest/hash verification → deterministic exported-model reference inference → golden vector → sensor-held-out scorecard/model card`

Nothing in this phase is connected to the Android production ISP.

## Student baseline

- HQ Student: 4,842,585 parameters.
- Lite Student: 1,947,933 parameters.
- normalization-free NAF-like encoder/decoder;
- 3x3 + 1x1 convolutions, add, multiply/SimpleGate, nearest resize, compact FiLM;
- no LayerNorm, ConvTranspose or full-resolution attention in the baseline;
- same 14-channel conditioning semantics as Phase 1/2;
- same bounded sigma-residual + posterior output semantics as the Teacher;
- high-SNR absolute correction collapses with sigma;
- clipped packed cells have zero correction authority through headroom.

## Distillation

The Teacher is SHA-256 verified, then hard-frozen. Student optimization combines ground-truth fidelity with Teacher clean RAW, Teacher sigma-normalized residual, Teacher posterior log-variance ratio, optional confidence and parameter-free selected feature-energy matching. Teacher parameters receive no gradients.

The reference curriculum uses progressive packed crops (256 → 512). Sensor train/validation/test splits remain disjoint.

## Optional architecture research

- Low-resolution global context: implemented as an explicit second spatial input for offline ablation; not selected/exported in baseline v1 without held-out + runtime evidence.
- Fine/coarse residual heads: implemented; both sum before one K-sigma bound; not selected without held-out evidence.
- Mamba/SSM: optional per masterprompt; deliberately not faked or selected because there is no evidence justifying extra mobile backend complexity.

## Export/integrity

Production-candidate export is flat little-endian FP16 with:

- deterministic tensor order;
- per-tensor offsets, shapes and hashes;
- complete weights hash;
- semantic model-content hash;
- exact CFA/normalization/conditioning/output schemas;
- architecture and primitive contract;
- parameter count;
- theoretical packed receptive field/minimum halo;
- Teacher/checkpoint/provenance hashes.

Corrupt weights or manifest tampering fail closed. Global-context research models are not silently accepted as baseline v1 exports. INT8/QAT is absent.

## Deterministic reference and golden vectors

Reference inference reconstructs the Student from the exported FP16 file itself, then runs FP16 weights/activations on CPU. The embedded contract fixture proves repeated reference output is bit-identical in the current environment.

Golden vectors bind to `model_content_sha256` and include internal SHA-256 for every array. They contain RAW/black/white, S/O, LSC, trust, headroom, full visible-control ABI metadata, expected bounded residual, posterior and final clean RAW.

No Vulkan tolerance is guessed in Phase 3; Phase 4 must derive parity tolerance from actual kernel evidence.

## Held-out sensor scorecard

Only declared `test_sensors` are scored. The report contains:

- Student fidelity/detail/noise/posterior/safety metrics;
- Teacher metrics;
- Student↔Teacher distillation gaps;
- evidence class per held-out sensor (`REAL_PAIRED_PRESENT` or `SYNTHETIC_FROM_CLEAN_ONLY`);
- invariant gates for finite outputs, clipped-sample identity and hard K-sigma behavior.

The tooling does not invent PSNR/detail/noise quality thresholds. Real production quality claims require governed real paired/dark-frame data plus explicit acceptance criteria.

## N006R regression boundary

Phase 3 adds only offline Python/config/test/documentation assets under `tools/spectra_neural_training`. It does not depend on or recreate any N006R-removed classical correction owner, does not modify `app/`, and adds no classical fallback/blend/runtime pixel owner.

## Not claimed

- no release-quality Teacher/Student weights are bundled;
- no real multisensor training run was performed in this sandbox;
- no Vulkan backend exists yet;
- no Android/NDK build or device inference was run;
- no on-device RAW10/RAW_SENSOR quality/latency/memory claim is made;
- no user-control production projection/OOD integration is claimed.

Those boundaries are intentional and hand off cleanly to Phase 4.
