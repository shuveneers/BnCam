# BnCam SF2 — baseline (2026-11-16)

- Main HEAD verified: `277826469978febf4e243b15363b8340840bd971`.
- Working branch: `feature/single-frame-engine-v2`.
- Existing Camera2, RAW10/RAW_SENSOR, physical S/O, Malvar/AMaZE/Auto Hybrid, color/tone and Vulkan-resident rendering are protected.
- The source and filter behavior of `PhysicalChromaDenoise.h` and `physical_chroma_denoise.glsl` are retained byte-identical.
- Prior luma owner: `PhysicalLumaDenoise.h`, previously enabled by default, with a single/noise-pressure adaptive directional residual algorithm.
- SF2: chroma-only is the default; old luma is only debug-enabled `LEGACY_REFERENCE`. `GUIDED_V2` is not active until separately integrated and verified.
- Existing same-RAW debug A/B can render `off.jpg` and `on.jpg`, and is reset to OFF afterward.
- No APK or on-device validation has been performed in this environment.
