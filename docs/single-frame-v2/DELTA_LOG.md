# SF2 delta log

| Delta | Status | Work | Test / limitation | SHA |
|---|---|---|---|---|
| SF2-D00-01 | CODE COMPLETE | Pin baseline and separate feature branch | HEAD verified, paths inspected | phase 0 commit |
| SF2-D00-02 | DEVICE VALIDATION PENDING | OFF default, debug-only LEGACY_REFERENCE, immutable chroma | Native stage contract added; no NDK build available | phase 0 commit |
| SF2-D00-03 | DEVICE VALIDATION PENDING | Same-RAW A/B resets to OFF | On-device JPEG comparison pending | phase 0 commit |
| SF2-D01-01 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Immutable S/O + pre/post-demosaic states + observational confidence; no new sensor owner | Native `SingleFrameNoiseContextTest` added, execution pending | phase 1 commit |
| SF2-D01-02 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Existing propagation untouched, gain applied once | Read-only state; pixel identity pending build | phase 1 commit |
| SF2-D01-03 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Context diagnostic in IspCore | Device/NDK tests pending | phase 1 commit |
| SF2-D02-01 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Malvar fixed kernel phase-average spatial Y correlation | Deterministic kernel test added, not yet executed | phase 2 commit |
| SF2-D02-02 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | AMaZE adaptive returns UNKNOWN; no invented linear correlation | Explicit uncertainty guard | phase 2 commit |
| SF2-D02-03/04 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Hybrid without local weights UNKNOWN; compact covariance contract | Read-only telemetry | phase 2 commit |
| SF2-D03-01/02 | CODE COMPLETE (diagnostic), DEVICE VALIDATION PENDING | CFA-aware tile rejection, normalized RAW Poisson-Gaussian S/O fit and confidence | Native synthetic test; no production override | phase 3 commit |
| SF2-D03-03 | CODE COMPLETE (guard), DEVICE VALIDATION PENDING | Explicit physical S/O fallback; estimate cannot mutate source | No resident readback or production hook | phase 3 commit |
| SF2-D04-01 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | New 3x3 physical-noise regularized fine guided-luma CPU reference, additive RGB residual, coherent line guard, exact bypass | Native flat/colour/sanity test added | phase 4.1 commit |
| SF2-D04-04 | CODE COMPLETE (contract), DEVICE VALIDATION PENDING | Rebased frozen pixel-identity test from obsolete `8aee2d29` to actual main HEAD `27782646`; protected chroma byte-identical; frozen legacy oracle unchanged; stage routing asserted. Historic test compared old chroma and failed on main already; changed expectations reflect explicit SF2 contract, not numerical weakening. | CI verification pending | test migration commit |
