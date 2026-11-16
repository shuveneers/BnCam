# SF2-D06 — Release checklist

**Baseline:** `701fcb0df6541d5ede0d0b451159934ae9a6928b` (frozen phase 5).

## Implemented, debug-only

- New `GUIDED_V2_CONFIDENCE = 5`, selected via existing debug stage JNI.
- Same full-resolution immutable demosaic guide for existing Guided Fine+Coarse candidate and deterministic confidence evidence.
- Six input/decision families: propagated physical Y noise, kernel-only demosaic uncertainty, coherent directional second difference, consistent edges, candidate significance, fine/coarse disagreement.
- Candidate correction is applied **once** to camera-linear Y as a neutral post-chroma RGB offset: `Ynew = YpostChroma + w*(DeltaFine+DeltaCoarse)`, `0 <= w <= 1`.
- No new CPU readback, Vulkan SSBO, GPU dispatch or push-constant bytes. Two existing compact coarse buffers are reused.
- Five-way same immutable RAW debugging: `off`, `on`, `guided`, `combined`, `confidence` with per-render diagnostic text. On completion, `finally` resets luma stage OFF.
- OFF/legacy/fine/fine+coarse and physical chroma implementation remain unchanged.

## Regression evidence

The exact previous phase-5 HEAD is checked out as a detached Git worktree during phase-6 CI. Its **20 original tests** run in full and are not redefined to fit the new source.

The phase-6 suite checks the new CPU oracle and the older native identity and physical-noise tests, plus a phase-6 whole-source fingerprint and routing contract. The real resident Vulkan GLSL source is compiled to SPIR-V.

The synthetic actual-candidate fixture verifies **more true line and textile contrast**, and also explicitly bounds its modest added pixel-MSE. No selective reporting of contrast alone is permitted.

Reference values and caveats are maintained in `RESIDUAL_CONFIDENCE_PROTECTION.md`; tests provide the reproducible exact sample.

## Not validated

- **No confirmed on-device numerical CPU/GPU parity** on HONOR Magic 8 Pro.
- **No confirmed Android build/APK** until optional phase-6 CI Android job succeeds and actually publishes its artifact.
- **No actual photographic improvement, temperature, latency or memory guarantee**.

The gate is **not production-default**. A photographic comparison can reject stage 5 without changing stage 4, stage 2 or the chroma-only default.

## Deliverables

Full branch archive: `https://github.com/shuveneers/BnCam/archive/refs/heads/deliverables/sf2-phase6.zip`

Delta ZIP is published as the GitHub Actions artifact `BnCam_SF2_F6_Delta`. Contents: changed files only (relative to the SHA above) plus `BnCam_SF2_F6_From_Phase5.patch`.

If the optional Android job succeeds, `BnCam_SF2_F6_Confidence_APK` becomes a separate artifact of the same run. If it fails, do not describe it as built.

**Next phase:** SF2-D07 integration and actual device qualification, not started here.
