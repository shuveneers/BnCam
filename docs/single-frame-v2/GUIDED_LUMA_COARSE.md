# BnCam SF2 — Phase 5: Guided Luma Coarse

**Release boundary:** phase 5 extends cumulative phase 4 at `ccf3e96976ae90b2ce59bcf774a4c5fae803555d` without modifying `main`. Fine-only (debug stage 2) remains separately selectable; the new **Fine+Coarse** route is debug-only stage 4. No production-default switch is authorized in this phase.

## D05.1 — Compact antialiased representation

`SingleFrameGuidedCoarse.h` provides a CPU reference from immutable post-demosaic RGB; `physical_luma_coarse.glsl` has a matching device-only downsample pass (mode 14).

- Resolution is `ceil(W/4) × ceil(H/4)`.
- A separable 4-tap binomial `[1, 3, 3, 1]/8` in both dimensions performs proper antialias filtering on the immutable demosaic Y. Four by four samples are consumed for each compact pixel; boundary input coordinates are clamped.
- Noise variance is **not** incorrectly divided by 16. For equal independent input sites the kernel-squared energy is `(20/64)² = 25/256` of the original site variance. Each site uses the existing propagated physical Y variance multiplied by its existing relative sigma map **once**.
- Two diagnostic values accompany the coarse luminance: variance assuming independent input sites, and the Cauchy upper bound `(Σ|w|σ)²` that does not require independence. **Neither establishes that AMaZE/Hybrid output noise is independent or that real covariance was measured.** The independent-assumption value is the working heuristic; if actual spatial correlations are material, the noise model still requires calibration against device RAW/JPEG evidence.
- Invalid/nonfinite input or invalid noise shape fails closed. No sensor S/O re-estimation, LSC rescaling, RAW reconstruction or new full-resolution copy is performed.

## D05.2 — Coarse guided bandpass

A separate 3×3 neighbourhood in the *coarse* domain generates a noise-aware, contrast-weighted **low-frequency bandpass** `mean(neighbours) − centre`. Weighted neighbours use spatial 2:1 axial/diagonal weighting and an exponential range gate.

- Opposite-neighbour differences protect large object transitions, illumination gradients and folds.
- Local curvature additionally gates coherent broad shapes.
- Fine and coarse consume the **same immutable pre-chroma/pre-luma demosaic guide**, not each other's output.
- Coarse authority is bounded to 0.38, with absolute correction bounded to 0.65 times the *independent-reference* coarse sigma. These are conservative heuristics, not independently validated optimum parameters.
- Constant fields have exact bypass. Away from borders, a symmetric affine gradient has no coarse correction. This is *not* tone mapping and never rewrites exposure curves.

## D05.3 — Frequency-separated composition

The fine 3×3 stage from phase 4 is unchanged. The new stage applies the fine additive Y delta and then bilinearly upsamples the compact coarse bandpass `(x − 1.5)/4, (y − 1.5)/4`, ensuring continuous tile boundaries.

Composition is a **neutral RGB addition** after the existing physical chroma denoiser, so R−G and B−G opponent differences remain unchanged. A compact coarse correction is not an absolute low-pass colour image and never overwrites a fine image.

Although the frequency split reduces duplicate filtering, **it does not mathematically prove perfect band orthogonality** or visual benefit on all textures. Debug stage 2 runs fine-only; if stage 4 does not improve photographs, coarse can remain disabled without a change to the product baseline.

## D05.4 — Vulkan integration and ownership

Existing `spectra_demosaic_resident.comp` gains *two optional* storage-buffer bindings: compact vec4 Y/variance data (binding 8), and one float residual per coarse cell (binding 9). The descriptor layout expands from 8 to 10 storage bindings; push constants remain unchanged at **128 bytes**.

For stage 4 only, `VulkanSpectraResidentDemosaicBackend.cpp` allocates two reusable device-only compact buffers (no host-mapped data). The command buffer executes:

1. mode 14: antialias/compact pre-chroma Y, writing binding 8;
2. explicit compute-shader write→read barrier for binding 8;
3. mode 15: guided coarse bandpass, writing binding 9;
4. explicit compute-shader write→read barrier for binding 9;
5. existing colour/AWB/CCM mode 3: Guided Fine plus bilinearly sampled coarse delta.

The two buffers use **20 bytes per coarse pixel**, i.e. roughly **1.25 bytes per full-resolution pixel**, ignoring rounding and allocator overhead. They are not generated for the normal OFF, legacy or fine-only route unless the buffers had previously been allocated in the same long-lived backend. An allocation failure fails closed to **Guided Fine only**, without blocking chroma. There is no new full-frame CPU readback, no new pixel-sized scratch buffer, and no extra normal-capture image pass.

The CPU failsafe uses `buildGuidedCoarse` only after a CPU image is already available because the normal resident route failed. This is a compact CPU reference path, not an automatic GPU→CPU download.

## D05.5 — Quality controls and limitations

`SingleFrameGuidedCoarseTest.cpp` tests antialias/variance propagation, checkerboard rejection, constant DC identity, natural gradients/shadows, strong step edges, textile-like alternating patterns, compact-grid interpolation, bounded noise correction, corrupted input and missing physical models. The synthetic noisy field compares **fine-only** with **fine+coarse**, and confirms the coarse path applies a measurable additional correction without exceeding a conservative MSE-regression tolerance. These tests do not establish device photographic superiority.

Native regression source guards additionally fingerprint every changed production file and reconstruct the original phase-0–4 ISP source when only reviewed debug stage 5 additions are removed. The phase-4 fine kernel and existing physical chroma implementation are frozen against the previous release.

### Same-RAW four-way debug comparison

With the existing developer-only `physicalLumaAbDirectory`, one immutable RAW master is rendered into:

- `off.jpg/.txt`: chroma-only product baseline (stage 0);
- `on.jpg/.txt`: frozen legacy luma reference (stage 1);
- `guided.jpg/.txt`: Guided Fine only (stage 2);
- `combined.jpg/.txt`: Guided Fine+Coarse (stage 4).

The debug `physicalLumaStage` integer selector accepts 0, 1, 2 and 4; other values fail to OFF, and the JNI selector is disabled in release builds. The four-way developer diagnostic resets stage to OFF in `finally`. There is no multi-render path in normal capture.

### Outstanding acceptance

**Device validation pending:** No signed or debug Android APK is guaranteed by the native workflow. Actual HONOR Magic 8 Pro CPU/GPU numerical parity, GPU allocation/descriptor runtime success, peak memory, noise/structure A/B on identical real RAW captures, large smooth shadow gradients, faint folds, hair, grass, text and textile retention, tile seams, JPEG output and capture latency/temperature remain to be verified.

Phase 5 must **not** be promoted into the normal product stage merely because synthetic tests and SPIR-V compilation pass. If additional real-world quality improvement over fine-only cannot be demonstrated, the coarse option should remain OFF. Phase 6 (Residual Confidence Gate) is a separate, not-yet-started delivery.
