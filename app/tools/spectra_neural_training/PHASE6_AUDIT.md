# SPECTRA Neural — Phase 6 Final Audit

Phase 6 closes the user-control and downstream-uncertainty contract around the single production Student RAW denoiser.

1. **Master Neural Denoise Strength** — visible profile-owned 0..1 authority; zero exits before inference.
2. **Luma Noise** — fixed orthonormal decomposition of the Student residual.
3. **Chroma Noise** — the same residual, not a second chroma filter.
4. **Detail Protection** — confidence/posterior gate that can only reduce mutation authority.
5. **Low-frequency Cleanup** — coarse component of the neural residual/context.
6. **Adaptive Response** — local sigma/inverse-SNR response; no capture-ISO threshold or gain fabrication.
7. **Character presets** — Natural/Clean/Texture/Night are transparent vectors over the same six visible controls.
8. **Profile NR ownership** — simple Denoise and Advanced SPECTRA pages write the same neural state; old Profile-NR values are compatibility-only and runtime-neutral.
9. **Posterior → SPECTRA Core** — full posterior stays GPU-resident; only compact R/G1/G2/B variance summary is read back.
10. **Uncertainty propagation** — posterior covariance proceeds through remaining LSC, demosaic, WB, CCM/HueSatMap and tone using the existing physical covariance chain.
11. **Creative protection** — propagated uncertainty informs detail/sharpness protection without adding another denoise pass.

Final invariants:
- one model, one bounded residual, one general RAW denoise pixel-owner;
- SPECTRA Core remains observer/evidence authority;
- no classical RAW denoise fallback is restored;
- backend/model/OOD failure publishes the original normalized RAW;
- no full-frame CPU neural path or posterior readback;
- RAW10 and RAW_SENSOR continue through the same pre-demosaic production handoff established in Phase 5.
