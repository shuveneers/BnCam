# SPECTRA Neural Phase 4 backend contract

- Vulkan is a backend of the existing BnCam Vulkan runtime; no second instance/device owner.
- Model package is deterministic FP16 C4 data, integrity checked before GPU upload.
- Primary input is a resident Vulkan buffer. AHardwareBuffer import is capability-gated and externally synchronized; only GPU-side Vulkan staging may be used as fallback.
- `enabled=false` and `Noise Reduction <= epsilon` are exact no-import/no-dispatch identity conditions.
- Non-zero `Noise Reduction` is a master neural authority applied after the K-sigma residual bound; it does not select a second denoiser and has no hidden minimum.
- The denoiser never reconstructs clipped signal. It preserves original saturation-mask/headroom evidence for the separate future `Neural Highlight Reconstruction` owner.
- The reserved future owner is downstream of SPECTRA Neural Denoise and before demosaic. Phase 4 implements only evidence preservation, never highlight reconstruction itself.
- No device model, manufacturer, sensor identity or lens identity is admitted to neural conditioning.
- No full-frame CPU RAW readback, CPU inference or classical denoise fallback is permitted.
- Phase 4 does not attach the backend to the production `IspCore` callsite; that placement belongs to Phase 5.
