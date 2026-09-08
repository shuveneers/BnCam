# Phase 4 Android Vulkan device validation gate

Run this gate after the N010A-F overlay is present in an Android/NDK build environment and before release acceptance.

Required checks:

1. Compile all eight neural compute shaders with the same `--target-env=vulkan1.1 -O` contract used by `SpectraNeuralBackend.cmake`.
2. Build the native backend with Vulkan + Android hardware-buffer headers and the pinned VMA integration.
3. Load the frozen Phase-4 `.vkmodel`; verify model/package SHA identities before upload.
4. Execute the frozen golden-vector RAW/physics input through the Vulkan backend at authority 1.0.
5. Compare final RAW residual/output and posterior against `phase4_parity.json`/Phase-3 golden hashes using the release tolerance chosen for the real FP16 Vulkan path. Any unexplained mismatch is a release blocker.
6. Verify `enabled=false` and `Noise Reduction=0` produce zero neural dispatches and unchanged RAW ownership.
7. Verify clipped/headroom evidence output reproduces the original saturation state and that clipped RAW is not reconstructed by the denoiser.
8. Exercise resident-VkBuffer input and, when supported by the device, direct synchronized AHardwareBuffer BLOB import. Exercise the Vulkan-buffer staging fallback when direct import is unavailable.
9. Confirm no full-frame host readback and no CPU/OpenCV neural fallback in diagnostics.
10. Record tile count, kernel dispatch count, inference latency, peak allocation and backend/model hash for HQ and Lite candidates used on-device.

A device result is not a training-quality claim. Release-quality denoise still depends on governed trained weights and the held-out sensor gates from the later quality phases.
