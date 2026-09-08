# SPECTRA Neural Phase 2 — source-level completion audit

Baseline contract: GitHub `34798c30d9364a4aecc0912e2c0110f2b9b34d25` + virtual N006R + frozen N007A..N007E.

## Scope completed

1. Dataset manifest and provenance.
2. RAW loader and canonical Bayer `[R,G1,G2,B]` packing.
3. Physics noise synthesis using normalized `Var = S*x + O`.
4. Real dark-frame bank/sampler.
5. PRNU, DSNU, row/column banding, heavy-tail read outliers, quantization, black residual and residual hot-pixel synthesis.
6. Remaining-LSC-aware synthesis and conditioning.
7. Sensor-disjoint train/validation/zero-shot-test splitting.
8. High-SNR / identity sampling.
9. Offline physics-conditioned Teacher architecture (~11M default parameters).
10. Hard sigma-bounded residual head.
11. Posterior log-variance-ratio / posterior-variance head.
12. Optional confidence head.
13. Fidelity-first loss stack.
14. RAW/detail/noise/posterior/safety evaluation stack.
15. Deterministic run provenance, checkpoint hashing and held-out evaluation route.
16. Source model card that explicitly makes no trained-release claim.

Student architecture, distillation, FP16 export, model manifest/hash and production golden inference vectors remain Phase 3 by contract.

## Phase-1 parity checks

- Spatial conditioning has exactly 14 channels and the order matches N007B exactly.
- Channel 13 (`metadata_trust`) now uses the same conservative trust principle as N007B: noise/metadata/black trust are always considered, and remaining-LSC trust participates when remaining LSC is active.
- `lens_shading_already_applied=true` with no explicit remaining map keeps the synthetic remaining-LSC condition at identity.
- Synthetic quantization uses the effective normalized RAW code span `white_level - black_level`, bounded by the declared bit depth.
- Model inputs contain no phone model, sensor model string, manufacturer, lens ID or sensor ID shortcut. Sensor ID remains dataset-governance/split metadata only.

## Real-paired truth hardening

CLEAN/PAIRED records require `clean_gt_method`. Curriculum Stage D consumes only true PAIRED records. If no PAIRED training records exist, Stage D is recorded as skipped with reason `no_real_paired_training_records`; synthetic CLEAN samples are never silently presented as real paired training.

## Safety properties covered by unit/reference tests

- all four standard Bayer layouts canonicalize correctly;
- sensor split leakage is rejected;
- Poisson/read variance tracks `S*x+O` numerically;
- dark-frame injection is bounded to training data and recentred per crop;
- identity sampling is materially lower noise than heavy sampling;
- true paired noisy RAW is consumed without re-synthesis;
- effective code-span quantization is deterministic;
- LSC-applied and active-LSC trust semantics are differentiated;
- Teacher output is K-sigma bounded;
- clipped-cell headroom forces exact identity;
- high-SNR absolute correction collapses with sigma;
- repeated eval inference is deterministic;
- default Teacher lies in the declared 10–40M research class;
- losses backpropagate and evaluation exposes fidelity/detail/noise/posterior/safety metrics;
- flat-field/new-frequency and repeatability helpers work;
- deterministic smoke training writes provenance, checkpoint hash, validation metrics, zero-shot metrics and model card;
- missing real-paired data causes an explicit Stage-D skip.

## Executed host validation

- Cumulative `pytest`: **25 passed**.
- Python `compileall`: **PASS**.
- Default Teacher parameter count: **10,984,057**.
- N006R removed-owner / forbidden-symbol scan over Phase-2 implementation code/config/tests: **0 hits**.
- Phase-2 delta path audit: **no `app/` production files touched**.
- Applicator audit: **no `APPLY*.py`, `.cmd` or `.bat` applicator is shipped**.
- Cache/bytecode audit: no `.pyc` or `.pytest_cache` is shipped.

## Explicit non-claims

- No real governed BnCam training dataset was supplied in this phase, so no release-quality Teacher weights were trained.
- No held-out-sensor image-quality score is claimed from the source-only smoke dataset.
- No Student, FP16 export, Vulkan parity, Android/NDK build or device runtime claim is made here; those belong to later phases.
- No classical fallback or production pixel mutation was introduced.
