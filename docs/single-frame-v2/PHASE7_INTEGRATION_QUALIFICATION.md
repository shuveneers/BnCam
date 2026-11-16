# BnCam SF2 — Phase 7: Integrated Device Qualification

**Build boundary:** cumulative `deliverables/sf2-phase7`, from frozen phase-6 HEAD `793768e296721e1eeede16724cdba983834443bf`. The `main` branch and every phase-6 pixel filter, chroma implementation, noise model, fixed/learned demosaic, tone pipeline and Vulkan kernel remain unchanged.

**Purpose:** The prior phases implement a debug-only luminance reconstruction, but only native synthetic tests and shader/Android builds had been achieved. Phase 7 adds an **actual executable on-device GPU-vs-CPU parity probe**, an explicit stage-routing/identity check, and a reproducible six-render same-RAW evidence package.

## 1. Integration

- `SingleFrameIntegrationQualification.h` constructs a fixed, reproducible 128×96 camera-linear RGB fixture containing two luminance regions, thin lines, a low-frequency pattern, fine simulated noise and nonzero opponent/chroma differences. It provides independent CPU expected pixels for stages 0, 1, 2, 4 and 5 (with kernel proxy both known and unknown).
- `SingleFrameIntegrationJni.cpp` is compiled into the **real Android libbncam.so**, not into a disconnected stand-alone test. It asks the actual `VulkanRuntime::executeSpectraResidentAwbCcm` backend to process that fixture with `physicalChromaValidationOnly=true`, so the output readback represents camera-linear RGB before AWB/CCM. The test-only GPU readback is **not part of normal capture**.
- The JNI probe checks each requested luma stage, whether coarse and confidence executed, finite pixel values, per-pixel max RGB discrepancy from the CPU reference, opponent/chroma preservation and GPU timing. The numeric qualification threshold is **1×10⁻⁴ linear RGB units** for all cases. This is a threshold to be tested, not a claim of completed CPU/GPU parity.
- After the gate and coarse GPU passes it dispatches **OFF again** and compares against the earlier OFF GPU result with a **2×10⁻⁶** repeat-identity bound. This detects stale SSBO/descriptor or stage state.
- The native probe accepts stage 5 once with unavailable adaptive demosaic spatial correlation and again with a fixed-kernel proxy. It **never treats unknown rho as measured zero**.
- The probe is called only by the **debug-source-set** broadcast `com.bncam.VALIDATE_SF2`. It executes off the UI thread and writes `files/sf2_qualification/report.txt` in the app-private data folder. The report includes the Android debug build's exact Git SHA and stage GPU timings.
- A separate debug action `com.bncam.TRIGGER_SF2_CAPTURE` avoids the old `TRIGGER_CAPTURE` action that has both a manifest receiver and a dynamic receiver; it prevents an unintended double capture during qualification.

## 2. Real RAW comparison

The existing debug-only `physicalLumaAb` option now renders **six times** from the SAME immutable source RAW16 buffer and identical metadata; the first five are the original phase-6 variants. The sixth is a repeat OFF **after** the GPU has processed the confidence variant:

| File | Debug stage | Expected path |
| --- | --- | --- |
| `off.jpg` | 0 | Chroma-only product baseline |
| `on.jpg` | 1 | Legacy luminance reference |
| `guided.jpg` | 2 | Guided Fine |
| `combined.jpg` | 4 | Guided Fine + Coarse |
| `confidence.jpg` | 5 | Guided Fine + Coarse + Residual Confidence |
| `off_repeat.jpg` | 0 | Product baseline again, after the other five stages |

Each JPEG has its own `.txt` containing actual native reconstruction telemetry. A `qualification.txt` contains the build SHA, immutable RAW CRC32, all six selected-stage getter checks and whether the two OFF JPEG byte strings match. JPEG byte identity is only a diagnostic: encoder metadata can differ without a change to linear RGB pixels.

The JNI probe's OFF-repeat float-buffer comparison, not the JPEG byte comparison, is the relevant deterministic GPU pixel-identity check.

**Normal capture:** this six-render branch is inaccessible without enabling the debug-only `physicalLumaAb` toggle. The native JNI probe has **no automatic production invocation**. The normal user capture retains the OFF luminance default.

## 3. Run on the HONOR Magic 8 Pro (Windows)

1. Download `BnCam_SF2_F7_Debug_APK` from the **successful** phase-7 GitHub Actions run and extract `app-debug.apk`. Install using `adb install -r path\\to\\app-debug.apk` and grant camera permissions in the app.
2. Connect the phone over ADB; confirm `adb devices` shows its serial and that the BnCam camera preview is running.
3. Execute a *synthetic GPU/CPU parity-only* qualification (does not capture an image):

   ```powershell
   python scripts/sf2_phase7_collect.py --output sf2_phase7_parity.zip
   ```

   The report is downloaded using binary-safe `adb exec-out run-as`, with no root. The script removes stale reports before triggering the test. Any missing or failing GPU case makes the command exit with status 2; no prior PASS is reused.
4. For the real same-RAW JPEG evaluation, place a static scene in front of the camera, choose RAW10 or RAW_SENSOR single-frame mode in BnCam, fix lighting and composition as closely as possible, then execute:

   ```powershell
   python scripts/sf2_phase7_collect.py --capture --output sf2_phase7_raw_evidence.zip --timeout 300
   ```

   The script uses the dedicated debug capture action and retrieves the six output JPEGs, diagnostic text files, stage manifest and parity report into one ZIP. The script checks each JPEG's markers, **actual native stage and GPU-execution telemetry**, selected-stage getter values and the presence of the master CRC. A CPU-fallback photo is **not** labelled a validated resident GPU photo.
5. Inspect all six images at matched 100% crops: low-lit walls and sky, dark smooth gradients, weak hair/grass strands, textile weave, small printed text, skin texture, hard edges/highlights, and any grid seams. Record retention of genuine structure, remaining luminance noise, chromatic stability and any false detail.
6. Repeat with representative ISO levels, RAW10 and RAW_SENSOR, and the actual production demosaic modes. A synthetic test does not validate neural/AMaZE noise correlation, sensor repeatability, or photographic acceptability.
7. For meaningful performance assessment, inspect the corresponding `.txt` GPU timings per stage and separately collect repeated warmed captures with the same dimensions and conditions. Do not compare a cold first-ever pipeline compilation with warmed stages without marking it.

The generated ZIP is **local**; it is not automatically sent to GitHub, OpenAI, or any external endpoint.

## 4. Acceptance and rejection criteria

**Automatable qualification:**

- The complete debug APK builds successfully, `lib/arm64-v8a/libbncam.so` is present, and both new JNI symbols are exported.
- Frozen phase-5 (**20**) and frozen phase-6 (**17**) tests pass in separate worktrees; the current phase-7 native suite and ADB collector offline tests pass.
- Actual phase-7 Vulkan resident shader compiles to SPIR-V.
- On the HONOR, all stage/proxy GPU executions succeed, each RGB max difference is ≤1×10⁻⁴, opponent integrity holds, and the final OFF GPU output matches initial OFF within 2×10⁻⁶.
- A real debug capture generates all six JPEGs and metadata with the requested stage, valid physical model and **confirmed resident GPU execution**. Missing or unverifiable stage telemetry fails closed.

**Still requires human review:**

- Noise reduction versus real scene detail retention for low-SNR hair/grass/textile.
- No gradient banding, new artifacts, lost skin texture, altered colour, or edge halos.
- Acceptable latency, memory, thermal load on the real HONOR, under repeatable conditions.
- Evidence that the confidence gate improves perceived photographic quality relative to the ungated fine+coarse stage; do not assume synthetic contrast gain equals a visual improvement.

**Release decision:** Stage 0 OFF is the production default in phase 7. Stages 1/2/4/5 remain explicit debug selections. **Phase 7 is a qualification candidate, not permission to promote Guided V2 into production**. Without a successful on-device parity report and real-photo review, the release remains *device qualification pending*.

## 5. Traceability

The phase-7 delta ZIP is computed from the exact phase-6 SHA `793768e296721e1eeede16724cdba983834443bf` and includes the new source, native/collector tests, qualification documentation and a base-relative `.patch`. Full project: `https://github.com/shuveneers/BnCam/archive/refs/heads/deliverables/sf2-phase7.zip`.

GitHub Actions `.github/workflows/sf2-phase7.yml` performs complete frozen-regression tests and a real Android APK compilation. The APK is downloadable **only if that actual job succeeds**.
