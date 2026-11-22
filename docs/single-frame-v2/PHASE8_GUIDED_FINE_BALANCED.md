# SF2-D08 — Guided Fine Balanced, technical delivery and device gate

Base: `deliverables/sf2-phase7-ui` at `445f2694260e6c83a9b71496ba49c20703439b55`.
Branch: `deliverables/sf2-phase8-balanced`. `main` is not changed.

## Scope

New opt-in stage **3 = GUIDED_V2_BALANCED** in debug builds only.
Existing stages 0 OFF, 1 Legacy, 2 Guided Fine, 4 Fine+Coarse and 5 Confidence retain their previous arithmetic and normal OFF remains the default. The colour, chroma, demosaic, physical S/O propagation, RAW normalization, tone mapping, and existing Vulkan backend/buffer allocation are unchanged.

### Hypothesis

The original Fine filter `guidedFine` estimates local 3x3 regression, excess scene variance, a 2-axis coherent structure gate, and a bounded correction `deltaY` added to all RGB channels.

Balanced uses the **same** original noise estimate and scene evidence, but scales the existing correction authority using:

```
textureRisk = max(coherentStructure, clamp(sceneVariance / (sceneVariance + expectedNoise + 1e-12), 0, 1))
balancedGain = 1.18 - 0.30 * textureRisk       // 0.88 ... 1.18
authority = clamp(0.78 * physical * wiener * gateFactor * balancedGain, 0, 0.78)
```

This does not measure true demosaic noise covariance and introduces no learned denoiser, new spatial variance model, larger GPU buffer or separate GPU pass. CPU and GLSL use equivalent expressions. Original Guided Fine has `balanced=false`, multiplier exactly 1.

The gain coefficients are **engineering candidates**, not fitted or validated optical parameters. They are intended to increase strength on smooth, noise-dominated areas and to decrease it near meaningful structure. They must be rejected if a blind device test shows loss of true texture or a measurable latency regression.

### Test evidence

- Frozen F7.1 suite executes from the base Git worktree and tests previous source invariants.
- Current D08 native suite tests deterministic noisy flat fields against known truth; coherent synthetic dark-thread contrast; unchanged additive RGB opponent channels; bypass on invalid noise/shape; and stage 0 product identity.
- Source guard reconstructs prior Fine CPU and GLSL source **byte-for-byte** by removing the isolated Balanced variation. Protected chroma/RAW/demosaic/Vulkan backend files must remain byte-identical to F7.1.
- Actual resident shader compiles with Vulkan 1.1 `glslc` before a full Gradle/NDK debug APK build. Packaged native JNI qualification probes are verified.
- On-device GPU/CPU parity probe now covers seven cases: stages 0, 1, 2, **3**, 4, 5 with unavailable-kernel proxy, and 5 with available-kernel proxy. Threshold remains 1e-4 linear RGB. OFF-repeat threshold 2e-6.
- Compare All now produces seven JPEGs from one RAW16 master: `off`, `on`, `guided`, `balanced`, `combined`, `confidence`, `off_repeat`; diagnostic `.txt` per render, immutable RAW fingerprint and stage manifest. Debug UI exports seven JPEGs.

## Release gate: do not claim photographic improvement from CI

**Synthetic accept:** Balanced flat-field MSE <= 1.01 × original Fine; Balanced synthetic thread contrast >= 95% of Fine and >=94% of known target; selected stage does not mutate OFF, invalid data safely bypasses and opponent channels are preserved. These are explicit limited engineering targets, not perceptual acceptance.

**HONOR Magic 8 Pro still required:** seven-case GPU/CPU report PASS on the shipped APK; complete same-RAW 7-image evidence; no new banding/halos/false structure; noise benefit with natural texture at 100% crops, stable colour and capture latency. The camera can only qualify a visually preferred stage after these tests. For controlled latency, compare warm GPU timings at equal sensor/exposure/capture dimensions.

## Decision

Keep stage 0 OFF as normal product default. If the new Balanced stage fails the device quality gate, reject the new stage for promotion and keep previous Fine/Confidence only as opt-in references. This delta ends the one-round Guided Fine development experiment; no automatic extra phase.
