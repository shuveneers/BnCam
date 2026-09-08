# SPECTRA Neural offline training/reference stack

This directory is deliberately **offline-only**. It is not linked into the Android app and contains no production inference fallback.

## Shared invariants

- canonical packed Bayer order is `[R,G1,G2,B]`;
- sensor identifiers exist only for dataset provenance and sensor-held-out splitting;
- sensor/phone/manufacturer/lens strings are never model features;
- the 14 spatial conditioning channels mirror the Phase-1 `SpectraNeuralConditioning` contract;
- physical shot/read noise is represented as `Var = S*x + O` in normalized RAW;
- remaining LSC is conditioning, not a second denoiser;
- high-SNR/identity sampling is a first-class training regime;
- all real data require source/license/calibration provenance; CLEAN/PAIRED records also require an explicit `clean_gt_method`;
- curriculum Stage D consumes only true PAIRED records and is explicitly skipped when none exist; it never relabels synthetic CLEAN samples as real paired data;
- no classical denoiser is used as runtime fallback or blended production owner.

## Phase 2 — Teacher/reference data stack

Phase 2 provides:

- governed dataset manifest + CFA-safe RAW loader;
- physics/dark-frame/PRNU/DSNU/banding/quantization/black-residual synthesis;
- LSC-aware conditioning;
- sensor-held-out splitting and identity sampling;
- offline physics-conditioned Teacher;
- bounded sigma residual + posterior uncertainty;
- fidelity-first losses/evaluation;
- reproducible Teacher training provenance.

## Phase 3 — Student/distillation/export stack

Phase 3 adds:

1. `model_student.py` — HQ/Lite normalization-free NAF-like mobile Student;
2. `distillation.py` + `train_student.py` — frozen-Teacher distillation from GT, Teacher clean/residual/posterior and selected feature energy;
3. `export_student.py` — deterministic flat little-endian FP16 weights + model manifest/hash;
4. `reference_inference.py` — deterministic inference from the exported FP16 bytes and golden-vector generation;
5. `heldout_scorecard.py` — per-sensor zero-shot held-out evaluation, Student↔Teacher gap metrics and invariant safety gates;
6. `student_model_card.py` — Student candidate model-card generation;
7. `PHASE3_ABLATION_DECISIONS.md` — explicit baseline/ablation decisions.

### Phase-3 sequence

- train/freeze a governed Phase-2 Teacher and retain its SHA-256;
- configure a sensor-disjoint Student run with `configs/phase3_student_distill.yaml`;
- distill the Student and freeze the resulting checkpoint;
- export that checkpoint to FP16 with `export_student.py`;
- generate golden vectors from the exported model with `reference_inference.py`;
- run the fully held-out scorecard with `configs/phase3_heldout_scorecard.yaml`;
- retain the Student manifest/hash, golden vector, scorecard and generated model card together.

The embedded `tests/golden/student_v1_contract` model is **test-only and untrained**. It exists solely to give Phase 4 stable exported bytes + golden outputs for backend parity. It is not a denoise-quality model and must never be shipped.

## Phase boundary

Phase 3 does **not** add Vulkan kernels, Android model loading, app integration, OOD runtime authority, visible control projection, multi-frame denoise or device-specific tuning. Those remain later phases.

The delta ZIPs are direct repository overlays. Python files in this directory are the actual offline training/reference implementation, not delta-applicator scripts.
