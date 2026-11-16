# SF2 delta log — delivery chain

| ID | Status | Implementation and files | Verification / constraints | Commit |
|---|---|---|---|---|
| SF2-D00-01 | CODE COMPLETE | Pin main baseline and branch; `BASELINE.md` | Main at 2778264 | 29bf7cc9 |
| SF2-D00-02 | DEVICE VALIDATION PENDING | Luma OFF default; debug-only legacy; `PhysicalLumaDenoise.h`, `IspCore.cpp` | Chroma files unmodified; phase-0 native CI succeeded | 29bf7cc9 |
| SF2-D00-03 | DEVICE VALIDATION PENDING | Same-RAW debug A/B resets OFF; stage test | Phone JPEG comparison pending | 29bf7cc9 |
| SF2-D00-04 | CODE COMPLETE | Rebased frozen identity fixture to main 2778264; phase-0 workflow | Native CI run 43834301957 success | ba509c90 |
| SF2-D01-01 | CODE COMPLETE / DEVICE VALIDATION PENDING | Immutable `SingleFrameNoiseContext.h`: effective physical S/O, pre/post-demosaic predicted residual covariance, observation, confidence, spatial and fusion provenance. No second S/O resolver. | C++ contract validates invalid/missing S/O and confidence; same-domain measurement only. No device test. | 2cda83cc |
| SF2-D01-02 | CODE COMPLETE / DEVICE VALIDATION PENDING | Native test `SingleFrameNoiseContextTest.cpp`, `CMakeLists.txt`, and `NOISE_CONTEXT_DOMAINS.md`. | Sensor S/O never re-gained; predicted covariance already includes existing LSC/exposure. Test explicitly checks one-time 2x gain produces 4x variance, context no added gain. | 895a1a16, cd0a5359, 2ef529ae |
| SF2-D01-03 | CODE COMPLETE / DEVICE VALIDATION PENDING | `IspCore.cpp` read-only observation and diagnostic line; `SingleFrameNoiseContextSourceTest.py`; phase-1 workflow. | Source regression reconstructs phase-0 ISP source byte-identically after removing ONLY new read-only lines; no chroma or shader changes. Native CI run 43836499294: **11/11 passed**. Android Gradle/APK/device tests NOT run. | dca4d404, 3a89cac7, 4837cfa1, 6c57db10 |

## Phase 1 release boundary

Phase 1 is branched from completed phase 0. The further experimental code in `feature/single-frame-engine-v2` is not included in the delivery and cannot be described as a validated successor. Next phase to deliver: **SF2-D02-01 Malvar spatial noise correlation**.

## Download model

- Full project per phase: GitHub archive of `deliverables/sf2-phase1`.
- Changes-only: GitHub compare patch `ba509c90...deliverables/sf2-phase1.patch`.
- APK: **not available**. The native workflow tests C++ and a source contract; it is not an Android build.
