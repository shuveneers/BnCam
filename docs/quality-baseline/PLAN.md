# Quality baseline (no tuning)

Reference commit: `cd319d6f65bfa070bafb4a299f313e717cdb5e82`.
Device: Honor BKQ-N49 / AUWE025B03006422. All raw/images/maps/logs remain in ignored `work/`.

## Capture order and operator input

| Tier | Camera / source | Physical scenes | Repeats / comparison |
| --- | --- | --- | --- |
| 1 | main / RAW_SENSOR | A, B, C, D, E | Three independent RAW captures per scene, each frozen-rendered through Malvar, AMaZE, Hybrid; two same-RAW renders per mode |
| 2 | main / RAW10 | A, B, D; C if clipping needs investigation | Match scene/framing/light; report actual exposure/ISO differences, never call different sensor inputs identical |
| 3 | ultrawide, tele, front | A, B, D; C only when indicated | Focused lens checks after main analysis; verify actual identity/RAW availability |
| 4 | main, affected lenses | F, G, H | Operator-controlled transitions, focus taps and real physical rotations |

Frozen replay captures are not nine independent live shutters or an end-to-end live-mode switching test. Three independent source frames expose capture variability while the per-frame replays isolate the algorithm under frozen controls. Additional live-mode shutter coverage remains explicit if required.

| Scene | Required physical setup |
| --- | --- |
| A | Good constant light; stable phone; text/fabric, white/gray, colored objects, dark patch; no extreme HDR |
| B | Lower light, stable phone, shadow details, dark and neutral surfaces; not complete darkness |
| C | Bright source/specular reflection, white and colored adjacent surfaces, dark surround |
| D | Fine text, raster, fabric/hair, diagonals and repetitive patterns |
| E | ColorChecker or fixed colored objects plus neutral white/gray; no absolute color-accuracy claim without characterized reference/illumination |
| F | Dark-to-bright and bright-to-dark; capture during transition, immediately after and after convergence |
| G | Near/far contrast; continuous AF, near/far taps, immediate/settled captures and quick second capture |
| H | Actual portrait, landscape-left and landscape-right; software rotation is not a substitute |

## Tools

1. Run `scripts/validateBnCamSingleFrame.ps1 -Serial AUWE025B03006422`; quality capture starts only after full PASS.
2. Leave the operator-selected camera/profile/source active and stable. `quality_capture.py` does not change camera settings.
3. Use a Python with numpy/Pillow (the bundled Codex runtime is available):

```powershell
& 'C:\Users\shuve\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' scripts/quality_capture.py --serial AUWE025B03006422 --scene A --lens-role main --output work/quality/main-rawsensor-A
```

The request is debug-only and frozen at shutter. It streams the owned canonical RAW buffer to app-private storage and reuses the production renderer with only demosaic selection changed. The ordinary publication follows. Each replay records native stats and SHA-256. A failed determinism check preserves partial files and fails the measurement. The collector always clears the opt-in request in `finally`.

**Latency scope:** quality captures execute six extra renders synchronously. Their existing processing/render clocks include that work and must never be reported as ordinary capture latency. Rows with `qualityReplayPath` are diagnostic workloads. Use `quality_latency.py` with the correct `--source` and `--camera` for ordinary live captures; it explicitly disables replay, records thermal state, and retains the existing publication/RAW evidence checks. See LATENCY.md. The script records thermal conditions but does not implement a status-0 benchmark gate.

`quality_analyzer.py compare --input <manifest.json> --output <separate-directory> [--rois <json>]` produces per-channel clipping, same-RAW difference maps and 100% / 200% nearest crops. It rejects mismatched RAW/geometry/controls/domain/orientation. `raw` analyzes checksum-verified canonical RAW; `hybrid` analyzes the actual 20-float debug mask layout. Synthetic metric tests: `scripts/test_quality_analyzer.py`.

Manual ROI JSON:

```json
{"rois":[{"id":"declared_gray","stage":"FINAL_JPEG","kind":"FLAT_NEUTRAL","box":[100,100,64,64]},{"id":"declared_edge","stage":"FINAL_JPEG","kind":"EDGE","profileAxis":"x","box":[200,200,64,32]}]}
```

Coordinates above are an example, not annotations of any actual scene. Declare ROI identity after inspecting actual images. Center crops are positional only. Spatial variance and RG/BG high-frequency residuals include scene content and are not isolated sensor noise or an automatic quality score.

## Stage attribution limits

Full JPEG replay includes the frozen production color/noise/tone chain. Canonical RAW evidence is available. Full resident intermediate stages after direct demosaic, WB/color and tone are not exported by this instrumentation. The existing standalone numerical GPU crop is an isolated diagnostic with its own priors, not the full production raw-finalize/color/noise path. Do not assign an artifact to DEMOSAIC/COLOR/NOISE_PROCESSING/TONE without independent stage evidence. Use UNKNOWN when evidence is insufficient.

No tuning is authorized during baseline collection. No sensor clipping, read-noise, color accuracy, sharpness winner or Hybrid quality benefit is inferred from algorithm disagreement alone.
