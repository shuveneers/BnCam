# SF2 phase statuses — delivery chain

**Delivery rule:** each `deliverables/sf2-phaseN` branch is a cumulative full project; each CI artifact ZIP contains ONLY that phase's changed files and a patch against the preceding phase. Original `main` is not modified.

| Phase | Status | Notes |
|---|---|---|
| 0 chroma-only | DEVICE VALIDATION PENDING | Code and native CI complete (run 43834301957); HONOR/APK pending |
| 1 physical noise foundation | DEVICE VALIDATION PENDING | Immutable context, physical noise provenance; native CI 11/11 (run 43836979969); HONOR/APK pending |
| 2 demosaic noise | CODE COMPLETE / DEVICE VALIDATION PENDING | Malvar/Bilinear phase-averaged fixed-kernel correlation-only proxy; explicit unknown AMaZE/Auto Hybrid/neural-posterior; native CI **14/14 passed** (run 43838446474); HONOR/APK pending |
| 3 RAW Poisson-Gaussian diagnostics | CODE COMPLETE / DEVICE VALIDATION PENDING | Optional CPU-RAW debug-only S/O-informed fit; no Vulkan readback, no S/O override; 16/16 native CI passed (43840240102); Android/phone tests pending |
| 4 guided fine | CODE COMPLETE / DEVICE VALIDATION PENDING | CPU reference + debug-gated resident Vulkan shader, same-RAW OFF/legacy/guided comparison; native 18/18 and SPIR-V shader CI passed (43844425567); no Android APK/device validation |
| 5 guided coarse | CODE COMPLETE / DEVICE VALIDATION PENDING | Two compact ¼-resolution device-only Vulkan passes, CPU oracle, debug Fine+Coarse stage 4, four-way same-RAW JPEG comparison; 20/20 native tests and shader compile passed (43849336343); Android/phone validation pending |
| 6 confidence gate | CODE COMPLETE / DEVICE VALIDATION PENDING | Deterministic orientation/residual/edge/fine-coarse confidence stage 5, CPU+GPU shader, same-RAW fifth debug JPEG; 20 frozen phase-5 tests + 17 current phase-6 tests and Vulkan SPIR-V CI passed; synthetic hair/textile contrast improves with modest MSE cost; HONOR/APK pending |
| 7 integration qualification | CODE COMPLETE / DEVICE EXECUTION PENDING | Debug-only Android GPU/CPU parity JNI, six-render same-RAW OFF-repeat, Windows ADB evidence collector; 20 frozen F5 + 17 frozen F6 + 18 current native tests, 5 collector tests and Vulkan GLSL CI passed; Android APK build/device execution tracked separately |

Phase 2 is based on **phase-1 HEAD** `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`. Only `DemosaicNoiseCorrelation.h` and `SingleFrameNoiseContext.h` differ under `app/src/main/cpp`.

### Limits

- `kernelProxyAvailable` is **not** a verified total post-demosaic spatial correlation. Input CFA spatial independence is an assumption, not a measured result. Pixel changes: none introduced by phase 2.
- No Android Gradle build, APK, Vulkan runtime benchmark, or HONOR Magic 8 Pro photographic validation was performed here.
- If phase 2 is installed as a complete project, it includes phases 0 and 1. Phase 2 does **not** include the unfinished `feature/single-frame-engine-v2` branch.

## Phase 3 release

Branch `deliverables/sf2-phase3`, based exactly on phase-2 SHA `9fba3da109825977630b2cf6cfa031c16dcba31f`. Read-only `RawPoissonGaussianEstimator` is used only when host RAW exists, the capture is single-frame and the developer dump flag is enabled. No mandatory RAW CPU path was added. The normal GPU-resident route reports `GPU_RESIDENT_NO_READBACK` and does not run the estimator. A numeric debug fit does not qualify as a replacement for Camera2/effective physical S/O. No Android APK or device validation was performed.

## Phase 4 release

- Based on `deliverables/sf2-phase3` at `76e98fbf09ba6905323b0356293118c8f6892381`.
- Branch: `deliverables/sf2-phase4`, cumulative full project. `main` unchanged.
- Normal product luma defaults to OFF. `GUIDED_V2` is available only via an opt-in debug JNI selector; the previous legacy stage remains separately available.
- CPU and GLSL Guided Fine are implemented; 18 native regression tests and SPIR-V compilation passed. The test assertions verify a synthetic flat-field noise reduction and preservation of step/thin-line patterns, but **do not** constitute real-world photographic validation or an on-device CPU/GPU parity result.
- Same immutable master RAW debug renders: `off.jpg`, `on.jpg`, `guided.jpg`, each with diagnostic text.
- Android Gradle/NDK APK build, GPU runtime, performance, CPU/GPU numerical parity and HONOR Magic 8 Pro RAW photographic validation remain pending.


## Phase 5 release

- **Base:** final phase 4 commit `ccf3e96976ae90b2ce59bcf774a4c5fae803555d`.
- **Branch:** `deliverables/sf2-phase5`. `main` remains unchanged.
- **Implementation:** `ceil(W/4) × ceil(H/4)` binomial antialias/downsample; propagated independent-reference and Cauchy-upper-bound noise variance; bounded edge-aware coarse residual on separate device buffers; bilinear upsample, additive camera-linear RGB luminance correction after fine/chroma. Normal OFF/fine-only stages are preserved.
- **Debug stage:** 4 = Guided V2 Fine+Coarse. Stages 0/1/2 still exist; release JNI refuses debug stage changes. Same RAW source creates `off.jpg`, `on.jpg`, `guided.jpg` and `combined.jpg`, each with a diagnostic text file.
- **Verification:** 20/20 native tests and real GLSL→Vulkan SPIR-V compilation passed (CI run 43849336343), including source baseline tests from phases 0–4. This does not test actual device allocation, execution, CPU/GPU numerical parity or real photographs.
- **Limitations:** Coarse noise independence is an *assumption*, not measured from spatially adaptive demosaicing. Its conservative authority remains experimental, and it can be disabled separately unless real photos demonstrate improvement over fine-only.
- **Pending:** Android Gradle/NDK build, APK, HONOR Magic 8 Pro visual assessment, performance, CPU/GPU parity and memory evaluation.

## Phase 6 release

- **Basis:** immutable phase-5 SHA `701fcb0df6541d5ede0d0b451159934ae9a6928b`; cumulative branch `deliverables/sf2-phase6`. Original `main` unchanged.
- **Gate:** native oracle `SingleFrameResidualConfidence.h` + GLSL `physical_luma_confidence.glsl`; per-pixel weight `0..1` on exactly the existing Guided Fine+Coarse candidate. Extra bescherming wordt alleen toegepast als de kandidaat een coherente georiënteerde structuur in de guide lijkt af te vlakken. De demosaic-kerncorrelatie is hoogstens een **onafhankelijke-site kernelproxy**, geen gemeten volledige rho.
- **Debugstage 5:** apart van OFF=0, legacy=1, fine-only=2 en fine+coarse=4. De bestaande identieke RAW-master-vergelijking maakt nu ook `confidence.jpg` met `confidence.txt`. Geen extra GPU-dispatch/SSBO/readback; debug alleen. Bij ontbrekende coarse buffer terugval naar fine-only.
- **Regressies:** de 20 ongewijzigde native/source tests op exact fase-5 worden opnieuw uitgevoerd in een detached Git-worktree; daarnaast 17 fase-6/native source tests en echte Vulkan SPIR-V shadercompilatie. Alle C++ asserts zijn met `-UNDEBUG` actief.
- **Kwaliteitsbewijs:** in de gecontroleerde synthetische testcase met echte fine/coarse-kandidaten stijgt de behouden contrastamplitude van haar/textiel richting de gespecificeerde ground truth. Pixel-MSE wordt iets slechter (vlak MSE 0.036119 → 0.038100, circa +5.5%). Deze trade-off is expliciet begrensd in regressies. Details: `RESIDUAL_CONFIDENCE_PROTECTION.md`.
- **Geen productieactivatie:** de acceptatie voor HONOR Magic 8 Pro foto's, CPU/GPU numerieke pariteit, GPU-latency, geheugen en warmte staat nog open. Een best-effort APK-build wordt alleen als geslaagd gerapporteerd wanneer de Android CI daadwerkelijk een bestand publiceert.


## Phase 7 qualification candidate

- **Base:** immutable phase-6 HEAD `793768e296721e1eeede16724cdba983834443bf`.
- **Branch:** `deliverables/sf2-phase7`, cumulative project. `main` remains unchanged.
- **No product pixel changes:** `IspCore.cpp`, `native-lib.cpp`, existing fine, coarse, confidence, chroma, noise authority, demosaic and Vulkan production kernels are byte-identical to phase 6.
- **New on-device proof path:** separate debug JNI in the real `bncam` shared library validates GPU output against an immutable synthetic CPU reference for OFF, legacy, fine, fine+coarse, confidence with both known fixed-kernel *proxy* and unknown demosaic provenance. It also checks that OFF recovers identically after running gated/coarse shader modes.
- **Actual RAW evidence:** debug-only captures five image variants plus `off_repeat.jpg`, all from one RAW master; each has a separate native statistics log. The manifest records a source-RAW CRC, build SHA and selected-stage getter checks.
- **Local device collection:** `scripts/sf2_phase7_collect.py` uses Python standard library + adb, triggers only one debug capture and fails closed if actual GPU execution cannot be verified. It produces one local evidence ZIP; no automatic remote upload.
- **Current verified CI:** frozen F5 **20/20**, frozen F6 **17/17**, current F7 **18/18** C++/Python tests, collector **5/5** tests, and Vulkan SPIR-V compilation passed on the phase-7 branch.
- **Android APK evidence:** only claim success if the `android-debug-apk` job in the exact final-HEAD phase-7 workflow succeeded and emitted `BnCam_SF2_F7_Debug_APK`. Compiling an APK does not mean GPU parity passed on a physical HONOR.
- **Do not promote:** stage 0 luminance OFF stays production default. Human evaluation of actual RAW-JPEG detail, noise, banding, skin/grass/hair/textile and thermal/latency remains a release prerequisite.
