# SPECTRA Neural model card — spectra_teacher_v1

**Status:** REFERENCE STACK / NO RELEASE WEIGHTS TRAINED YET

- Model version: `spectra_teacher_v1`
- Teacher version: `spectra_teacher_v1`
- Student architecture: not applicable in Phase 2; Student belongs to Phase 3
- Teacher architecture: NAF-like physics-conditioned multi-scale U-Net, default ~11M parameters
- Precision: offline training precision is run-configured; production precision is not claimed by this Teacher
- Supported CFA: standard Bayer canonical `[R,G1,G2,B]`
- Training sensors: not populated until a real governed dataset manifest is supplied
- Held-out sensors: not populated until the split is supplied
- Noise ranges: `Var=S*x+O` plus real dark-frame/electronics/spatial randomization
- Posterior calibration: not yet calibrated on held-out release sensors
- Safety K: `4.0 sigma` starting research value, explicitly subject to held-out ablation
- Dataset versions: none attached to this source-only model card

## Known failures / unproven areas

- No real release training has been executed by this delta.
- No held-out-sensor quality claim exists yet.
- No production Student, FP16 export, Vulkan parity or mobile runtime claim exists; those belong to later phases.
- Visual microdetail/texture/depth validation still requires the governed real-scene dataset.

## Non-goals

The Teacher is offline only. It does not learn color/tone/style correction and does not authorize a classical denoise fallback.
