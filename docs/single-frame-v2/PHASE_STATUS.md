# SF2 phase statuses — delivery chain

**Delivery rule:** phase branches are cumulative; each ZIP is a full project at that phase, and a `.patch` link identifies only new changes from the preceding phase. Main is not modified.

| Phase | Status | Notes |
|---|---|---|
| 0 chroma-only | DEVICE VALIDATION PENDING | Code complete; native CI succeeded (run 43834301957); phone comparison and APK pending |
| 1 physical noise foundation | DEVICE VALIDATION PENDING | SF2-D01-01/02/03 code complete, 11/11 native CI passed (run 43836499294); immutable context read-only; no APK |
| 2 demosaic noise | NOT STARTED | Separate experimental feature-branch work exists but is **not** phase-2 deliverable or device validated |
| 3 RAW estimator | NOT STARTED | Separate experimental feature-branch work only |
| 4 guided fine | NOT STARTED | Separate experimental feature-branch work only |
| 5 guided coarse | NOT STARTED | Unimplemented |
| 6 confidence gate | NOT STARTED | Separate experimental feature-branch work; latest prior CI failed; not promoted |
| 7 integration | NOT STARTED | Requires phase sequencing and device tests |

Phase-1 delivery branch: `deliverables/sf2-phase1`, based on `deliverables/sf2-phase0` at `ba509c90f71c63e3493aecbdef5e7c8890ede870`.

No APK build or HONOR Magic 8 Pro validation was performed. An unchanged camera-pixel path was verified by native source contract, not by live device screenshots.
