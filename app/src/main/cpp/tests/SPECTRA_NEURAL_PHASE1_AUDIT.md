# SPECTRA Neural — Phase 1 Foundation Audit

## Baseline

- Repository: `shuveneers/BnCam`
- GitHub baseline commit: `34798c30d9364a4aecc0912e2c0110f2b9b34d25`
- Required predecessor: `DELTA_N006R_FINAL_PRE_NEURAL_CLEANUP`
- Phase-1 apply order: N007A -> N007B -> N007C -> N007D -> N007E

The phase is designed against the virtual post-N006R baseline. N006R remains the owner of the pre-neural classical cleanup; Phase 1 does not restore any header or pixel owner removed by N006R.

## Retained baseline contracts used

Phase 1 reuses existing BnCam physics/contracts instead of duplicating them:

- `RawCfaContract.h`: explicit standard-Bayer layout resolution and CFA phase handling.
- `RawDomain.h`: raw-domain black/white, normalization and lens-shading state.
- `SpectraNoiseCalibration.h`: canonical shot/read noise calibration semantics.
- `RawSpatialNoiseCalibrationPolicy.h`: preserved `Var = S*x + O` and gain-propagation semantics.
- `SpectraNoisePropagation.h`: retained downstream uncertainty/noise propagation domain.
- `SpectraNoiseProfileUncertainty.h`: retained uncertainty/evidence concepts.

The new Phase-1 runtime headers depend only on retained post-N006R contracts. They do not include or require any N006R-deleted classical owner.

## New ownership boundary

### SPECTRA Core

`SpectraCoreSnapshot.h` is observer/evidence state only. It owns no pixel buffer, filter kernel or pixel mutation API. It contains the canonical Bayer mapping, physical noise coefficients, metadata trust, black-level residual evidence, remaining-LSC state and structured-noise evidence needed by neural conditioning.

### Neural conditioning

`SpectraNeuralConditioning.h` converts the snapshot and per-cell sensor samples into deterministic physics conditioning. It does not denoise. The base conditioning schema is versioned and contains normalized RAW, log-sigma, remaining-LSC gain, metadata trust and headroom. Structured-noise evidence remains conditioning only.

### Controls and bypass

`NeuralRawDenoisePolicy.h` is the single Phase-1 owner of neural denoise controls and exact invocation policy. Neural Off or effectively zero master authority skips inference. Invalid physics, unsupported CFA, missing required LSC, invalid model integrity/schema, unavailable backend or OOD state bypass neural processing.

There is no classical denoise fallback branch.

### Backend-neutral ABI

`NeuralRawDenoiseBackend.h` defines GPU/external resource views and request/result contracts without implementing a quality model. A neural result is publishable only when inference completes and both clean packed RAW and posterior variance outputs are valid. Failure publishes the original input.

There is intentionally no full-frame host/CPU image pointer in this ABI.

### Telemetry

`SpectraNeuralTelemetry.h` records input physics, model/runtime identity, visible authorities, sigma-normalized correction statistics, per-CFA correction bias, posterior uncertainty and bypass/safety state. It has no correction authority.

## Phase-1 golden contract tests

`SpectraNeuralFoundationTest.cpp` covers:

- RGGB, GRBG, GBRG and BGGR -> canonical `[R,G1,G2,B]` mapping;
- crop/CFA-phase handling;
- rejection of non-Bayer CFA and incomplete packed extents;
- black/white normalization;
- shot/read `S/O` variance and sigma;
- remaining-LSC `g^2` variance and `g` sigma propagation;
- headroom behavior;
- 14-channel conditioning construction and exact identity LSC when no remaining map exists;
- missing-required-LSC bypass;
- Off/zero/backend-unavailable bypass;
- valid inference decision;
- backend resource-shape validation;
- fail-closed original-input publication on incomplete/failed results;
- neural publication only for complete clean-RAW + posterior-variance output;
- telemetry initialization and representative value storage.

Reference verification command:

```text
g++ -std=c++17 -Wall -Wextra -Werror -O2 \
  -Iapp/src/main/cpp \
  app/src/main/cpp/tests/SpectraNeuralFoundationTest.cpp \
  -o spectra_neural_foundation_test
./spectra_neural_foundation_test
```

Expected result: process exits with status `0` and no output.

## Forbidden-dependency audit

The Phase-1 C++ headers/tests were scanned for the N006R-deleted classical owners. Result: **0 hits**.

No Phase-1 delta changes `IspCore.cpp`, `IspCore.h` or `CMakeLists.txt`. This is deliberate: Phase 1 establishes and tests the contract without connecting a placeholder model or a hidden image path. Runtime/Vulkan inference integration belongs to the later backend phase.

No Phase-1 delta contains `.py` or `.cmd` applicators.

## Deliberate Phase-1 limits

- v1 accepts standard Bayer only; unsupported CFA families fail closed.
- No quality model, weights, shader or inference implementation is introduced.
- No production pixel path is connected yet.
- No ISO-to-denoise-strength shortcut is introduced.
- Headroom span is a future model/runtime parameter and is not guessed here.
- Black-level residual estimation is represented as bounded evidence/state; an estimator is not fabricated in Phase 1.
- Remaining-LSC maps are represented by a strict condition/resource contract; no duplicate LSC application is introduced.

## Phase-1 status

All requested foundation ownership/conditioning/bypass/ABI/telemetry contracts are coherent and covered by the standalone golden contract test. Phase 1 stops here by design before a neural quality model is connected.
