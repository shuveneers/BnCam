# SF2 delta log — incremental delivery chain

| ID | Status | Implementation | Evidence / limitations |
|---|---|---|---|
| SF2-D00-01 | CODE COMPLETE | Main SHA 2778264 pinned; separate release chain | Baseline |
| SF2-D00-02/03/04 | CODE COMPLETE / PHONE PENDING | Chroma-only default, debug-only legacy luma, same-RAW A/B; frozen source and pixel contracts | Phase-0 native CI success 43834301957; no APK |
| SF2-D01-01 | CODE COMPLETE / PHONE PENDING | Immutable `SingleFrameNoiseContext`, existing physical S/O and propagated residual states | Native tests; no second S/O resolver |
| SF2-D01-02 | CODE COMPLETE / PHONE PENDING | `SingleFrameNoiseContextTest` and domain contract | One-time LSC/spatial gain and RAW sensor domain tested |
| SF2-D01-03 | CODE COMPLETE / PHONE PENDING | `IspCore.cpp` diagnostic-only object construction; native source identity | Phase-1 11/11 native CI 43836979969 |
| SF2-D02-01 | CODE COMPLETE / PHONE PENDING | `DemosaicNoiseCorrelation.h`: R/Gr/Gb/B fixed kernel impulse overlap, phase-averaged camera-linear Y correlations for Malvar/Bilinear at four offsets; explicit unknown AMaZE/Auto Hybrid/neural posterior | `DemosaicNoiseCorrelationTest` independently reconstructs impulse kernels and cross-checks existing fixed-RGB covariance; no spatial independence measured |
| SF2-D02-02 | CODE COMPLETE / PHONE PENDING | Correlation attached to immutable `SingleFrameNoiseContext` with source provenance; diagnostics only show rho if proxy available | `SingleFrameDemosaicContextTest` verifies unknown values never published as rho=0 |
| SF2-D02-03 | CODE COMPLETE / PHONE PENDING | `Phase2ReadOnlySourceTest.py` and phase-2 native workflow with changed-files artifact ZIP | CI **14/14 passed** at 43838446474, including phase-0/1 regression and source identity; no APK/device test |

| SF2-D03-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `RawPoissonGaussianEstimator.h`: optional read-only normalized pre-LSC Bayer variance–signal fit; existing effective S/O-guided tile rejection; no calibration authority | Synthetic CFA native tests; physical S/O unchanged |
| SF2-D03-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` reads existing CPU RAW only when debug enabled and single-frame; normal resident GPU skips without readback | Phase-0/1/2 source regression preserved; no APK |
| SF2-D03-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | C++ tests, phase-3 source contract and GitHub ZIP packaging | 16/16 native CI succeeded at run 43840240102; no real RAW/phone calibration |

| SF2-D04-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `SingleFrameGuidedLuma.h` full-res bounded 3×3 guided fine CPU oracle; additive scalar RGB residual, local physical noise, coherent-line gate, strict bypass | Synthetic native flat noise/detail/chroma tests passed; device pending |
| SF2-D04-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `physical_luma_denoise.glsl` Guided Fine; legacy GLSL body preserved exactly | Actual Vulkan `spectra_demosaic_resident.comp` SPIR-V compile in GitHub CI; GPU pixel parity pending |
| SF2-D04-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | Vulkan color transform request carries explicit OFF/legacy/guided stage via existing mode-3 push bits; no ABI/readback changes | Native source isolation plus GLSL compile; no device GPU run |
| SF2-D04-04 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` routes explicit debug Guided Fine through resident Vulkan and CPU fallback, retaining OFF as normal default and legacy reference | Frozen OFF/legacy ISP source identity test passed; no full Android build |
| SF2-D04-05 | CODE COMPLETE / DEVICE VALIDATION PENDING | Debug JNI/Kotlin stage control and same-RAW three-JPEG quality comparison (`off.jpg`, `on.jpg`, `guided.jpg`) | Source contracts check explicit stage and guaranteed reset; photo assessment pending |
| SF2-D04-06 | CODE COMPLETE / DEVICE VALIDATION PENDING | Phase-4 synthetic test, protected-source tests, native and GLSL CI, changes-only ZIP | **18/18 native tests and SPIR-V compile passed** in run 43844425567; no APK/phone |

| SF2-D05-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `SingleFrameGuidedCoarse.h` CPU oracle: quarter-resolution antialiased 4×4 binomial Y, independent-reference variance and Cauchy upper bound, 3×3 gated coarse bandpass, bilinear upsample, exact bypass | Deterministic synthetic noise/shadow/textile/seams plus invalid-model tests |
| SF2-D05-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `physical_luma_coarse.glsl`: modes 14/15 and quarter-size Y/variance+residual buffers on bindings 8/9. Stage 4 combines the frozen Guided Fine and new coarse additive Y delta | Actual production Vulkan resident GLSL compiled to SPIR-V; GPU numerical parity pending |
| SF2-D05-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | Vulkan backend: two persistent device-only compact buffers, explicit compute barriers, 128-byte push ABI retained, fine-only fallback on coarse allocation failure | Source guard and GLSL compile passed; no phone benchmark or extra normal readback |
| SF2-D05-04 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` CPU fallback builds coarse only from the existing immutable post-demosaic guide; fine-only remains selectable | Frozen phases 0–4 source regressions maintained, fine kernel and chroma unchanged |
| SF2-D05-05 | CODE COMPLETE / DEVICE VALIDATION PENDING | Debug JNI/Kotlin stage 4 and same-RAW four-way `off.jpg`, `on.jpg`, `guided.jpg`, `combined.jpg`; reset stage OFF in `finally` | Strict source guard and native tests; APK/device test pending |
| SF2-D05-06 | CODE COMPLETE / DEVICE VALIDATION PENDING | `SingleFrameGuidedCoarseTest.cpp`, `Phase5CoarseSourceGuardTest.py`, CMake, CI and delta ZIP | **20/20 native tests and production Vulkan SPIR-V compile succeeded**; synthetic controlled MSE fine-only 0.281483 vs fine+coarse 0.268113 (about 4.75% reduction). Actual photo benefit unproven |


| SF2-D06-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | `SingleFrameResidualConfidence.h`: deterministisch scalar gategewicht 0..1 op bestaande fine+coarse residual; physical noise, kernelproxy-onzekerheid, coherente tweedeverschillen, randconsistentie, residual-significantie en fine/coarse-oppositie | Echte native oracle gebruikt geen extra S/O/ISO-gain, geen tone/sharp/chroma; 0..1/bypass tests |
| SF2-D06-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | `physical_luma_confidence.glsl` gebruikt identieke formules binnen de bestaande kleurshader; push bit 5 transporteert alleen kernelproxy-provenance | Production shader naar Vulkan1.1 SPIR-V gecompileerd; GPU device-pariteit nog onbekend |
| SF2-D06-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | Expliciete debugstage `GUIDED_V2_CONFIDENCE=5`, hergebruik van twee compacte coarse-SSBOs, geen nieuw ABI, dispatch of full readback | OFF/legacy/fine-only/fine+coarse behouden; bij coarse alloc fail terug naar fine-only |
| SF2-D06-04 | CODE COMPLETE / DEVICE VALIDATION PENDING | CPU fallback gebruikt bestaande immutable guide en dezelfde kandidaat, met eenmalige gated neutrale RGB-Y correctie; geen dubbele chroma-filter | Broncode-inventaris/pinning, oude fase-5-worktree tests |
| SF2-D06-05 | CODE COMPLETE / DEVICE VALIDATION PENDING | Debug JNI/Kotlin vijfde identieke RAW-JPEG (`confidence.jpg`) en diagnose; reset naar OFF in `finally` | Native/source contract; echte Android runtime/APK pending |
| SF2-D06-06 | CODE COMPLETE / DEVICE VALIDATION PENDING | Native synthetic hare/weak-line/textile/edge/flat/invalid tests + daadwerkelijke fase-4/5 kandidaatcontrasten met exacte synthetische truth | Hair A .017633→.017868 (truth .020); hair B .009476→.009645 (truth .011); textile .010259→.010703 (truth .011). Flat MSE .036119→.038100: gedocumenteerde trade-off, geen apparaatclaim |
| SF2-D06-07 | CODE COMPLETE / DEVICE VALIDATION PENDING | Phase-6 CI voert **20 originele fase-5-tests** op onveranderde SHA uit, plus **17 actuele fase-6-tests**, echt shader SPIR-V en changes-only ZIP | Android/phone quality acceptance pending; standalone release en `.patch` tegen vorige SHA |

| SF2-D07-01 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | `SingleFrameIntegrationQualification.h` deterministic 128×96 camera-linear CPU oracle for OFF, legacy, fine, coarse and confidence; pure C++ tests check gate contraction and opponent invariance | F7 current native CI, new qualification C++ test passed; no on-phone parity numbers |
| SF2-D07-02 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | `SingleFrameIntegrationJni.cpp` part of real Android libbncam.so; test-only `executeSpectraResidentAwbCcm` GPU readback compares 6 stage/proxy cases with CPU; OFF re-run checks stale GPU state | Android packaging and real GPU execution must both be proven separately |
| SF2-D07-03 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | Debug-only receiver `com.bncam.VALIDATE_SF2`, async report with Git SHA; unique `com.bncam.TRIGGER_SF2_CAPTURE` avoids duplicate manifest-triggered captures | Kotlin compiled only after successful Android APK job; dedicated on-device invocation still pending |
| SF2-D07-04 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | ImageUtils six same-RAW renders including `off_repeat.jpg`, separate stats, source CRC32, stage getter/revision manifest, OFF reset | Static source tests pass; JPEG byte identity is informational, not a pixel proof |
| SF2-D07-05 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | `scripts/sf2_phase7_collect.py`: Windows-compatible binary-safe adb private-file collector; fail-closed per-stage GPU telemetry checks and evidence ZIP | Five offline Python unit tests passed; user HONOR evidence still pending |
| SF2-D07-06 | CODE COMPLETE / DEVICE QUALIFICATION PENDING | F7 workflow reruns frozen F5 20/20 and F6 17/17, current F7 18/18, collector 5/5, production GLSL SPIR-V, mandatory Android assembleDebug and JNI symbol check, changed-files delta | Offline native & Vulkan passed in F7 CI; phone GPU parity and photographic comparison not yet executed |

## Exact release boundary

- Phase-1 base SHA: `ea7f4b45c41e5770cd8d3938bfd1133efc40aa65`.
- Phase-2 release branch: `deliverables/sf2-phase2`.
- Phase-3 release branch: `deliverables/sf2-phase3` based on phase-2 SHA `9fba3da109825977630b2cf6cfa031c16dcba31f`.
- Full project downloadable via the GitHub branch ZIP.
- Changes-only ZIP downloadable as the `BnCam_SF2_F2_Delta` GitHub Actions artifact; its ZIP includes changed files and a reproducible `.patch` against phase 1.
- The unrelated `feature/single-frame-engine-v2` experiment is not included.
- Phase-4 branch: `deliverables/sf2-phase4`; phase-3 base SHA: `76e98fbf09ba6905323b0356293118c8f6892381`.
- Phase-5 branch: `deliverables/sf2-phase5`; frozen phase-4 base SHA: `ccf3e96976ae90b2ce59bcf774a4c5fae803555d`.
- Phase-6 branch: `deliverables/sf2-phase6`; exact phase-5 base SHA: `701fcb0df6541d5ede0d0b451159934ae9a6928b`.
- **Next closed phase: SF2-D07 Complete Pipeline Integration.** Not started on phase 6.
