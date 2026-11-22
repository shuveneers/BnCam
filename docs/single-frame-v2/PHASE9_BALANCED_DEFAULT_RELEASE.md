# SF2 FINAL — Guided Fine Balanced as the normal single-shot RAW mode

Base: `deliverables/sf2-phase8-balanced` at `572ef4546a6fd0ecdb67dfb7f87d64f0f452a132`.

## Scope and safety

- Guided Fine Balanced (stage 3) is selected by default in the actual native IspCore renderer when `meta.singleShotRaw && meta.calibration.spectraProcessingMode == 0` and the physical post-demosaic noise model validates.
- All multi-frame, Spectra ON, and unsupported/invalid-noise situations stay on the chroma-only OFF fallback.
- The prior default is preserved as a zero-modification rollback from the native debug selector, while `main` and frozen D08 remain untouched.
- Legacy/Fine/Coarse/Confidence UI choices are **removed** and normal capture never intentionally selects them. The older kernels are **not physically deleted** in this candidate: those CPU/GPU reference paths are needed for the existing seven-case integrity/parity qualification and reliable rollback before final device signoff. Their code is not executed in normal product capture.
- Product enablement is native, not dependent on launching Developer Settings or toggling debug state. On fresh launch the boolean debug override is false, and the eligible default resolves to stage 3. A debug-only override can force OFF. Clearing the override restores the product routing.
- Same-RAW diagnostic capture is reduced to `off.jpg`, `balanced.jpg`, `off_repeat.jpg`; associated telemetry and RAW CRC are still recorded. Critically, the returned normal JPEG is `balanced.jpg`, not the former confidence render.
- GPU/CPU numerical parity remains seven cases for the archived internal renderer stages, and the OFF-repeat buffer check remains active. No native shaders, tone, sharpening, S/O, chroma, demosaic or camera capture internals are otherwise changed.

## Qualification

Automated CI runs the frozen D08 native suite independently, plus the current source and product default contracts, fail-closed ADB collector tests, real resident Vulkan shader compilation, Android APK assembly and packaged JNI validation.

The product default C++ test checks single RAW + Spectra OFF promotes Balanced, but single RAW + Spectra ON and multi-frame both resolve to OFF, plus the debug rollback and reset. This is not a substitute for capture testing on a HONOR Magic 8 Pro.

## Required HONOR smoke test

1. Install APK over the prior debug build if Android signature permits. Before testing, restart the application; under Developer Settings **Guided Fine Balanced** must be ON by default.
2. Select RAW10, single-frame, Spectra OFF. Take one **normal camera capture**, not Compare All; confirm `baselinePhysicalLuma.stage=GUIDED_V2_BALANCED_DEFAULT`, valid physical model and `gpuExecuted=true` in native stats when available.
3. Take a Compare OFF/Balanced set, confirm three images and OFF repeat identity. Its ordinary output JPEG must now be Balanced.
4. In Developer Settings set Balanced OFF; take a normal RAW JPEG; confirm stage OFF (rollback). Switch back ON and re-test.
5. Switch to RAW_SENSOR; take a normal single-shot JPEG. Next try multi-frame and Spectra ON; the stage must be OFF on those unsupported routes.
6. Validate seven GPU/CPU fixture cases once more (same native kernels, different product routing). Check 10 warmed latency measurements each on OFF/Balanced and 20 sequential ordinary captures for memory/thermal stability.

## Acceptance boundary

The code is delivered as a **device-test candidate**, not an unconditional camera-quality guarantee. External optics and device-specific processing modes still require controlled tests. Only qualified route combinations may receive the new default. No destructive deletion of legacy reference kernels until the user approves the measured default in ordinary captures.
