# SF2 delta log

| Delta | Status | Work | Test / limitation | SHA |
|---|---|---|---|---|
| SF2-D00-01 | CODE COMPLETE | Pin baseline and separate feature branch | HEAD verified, paths inspected | phase 0 commit |
| SF2-D00-02 | DEVICE VALIDATION PENDING | OFF default, debug-only LEGACY_REFERENCE, immutable chroma | Native stage contract added; no NDK build available | phase 0 commit |
| SF2-D00-03 | DEVICE VALIDATION PENDING | Same-RAW A/B resets to OFF | On-device JPEG comparison pending | phase 0 commit |
| SF2-D01-01 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Immutable S/O + pre/post-demosaic states + observational confidence; no new sensor owner | Native `SingleFrameNoiseContextTest` added, execution pending | phase 1 commit |
| SF2-D01-02 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Existing propagation untouched, gain applied once | Read-only state; pixel identity pending build | phase 1 commit |
| SF2-D01-03 | CODE COMPLETE (source), DEVICE VALIDATION PENDING | Context diagnostic in IspCore | Device/NDK tests pending | phase 1 commit |
