# BnCam SF2 — Phase 1 domain and provenance contract

**Status:** CODE COMPLETE pending CI and device validation. This stage observes existing noise evidence; it does not change ISP pixels.

| Field | Domain and meaning | Existing owner |
|---|---|---|
| `sensorS[4]`, `sensorO[4]` | Normalized sensor/Bayer variance `S*x + O`; channel order R, Gr, Gb, B. No exposure/ISO/LSC is applied here by SF2. | Existing capture-local `effectiveS/O` resolver |
| `postFinalize` | Existing **predicted residual covariance** immediately before demosaic. The seed uses channel predicted visible variance, measured residual scales and, when applicable, neural posterior. Existing LSC and spatial exposure transformations are already accounted for upstream. This is not raw sensor variance. | `residualNoiseState.preDemosaic` |
| `postDemosaic` | Existing camera-linear RGB **predicted** covariance after actual demosaic mode selection. Not post-chroma/post-WB/post-CCM. | `residualNoiseState.postDemosaic` |
| `observedPostDemosaicY` | Optional empirical robust residual variance: same stage, sufficient accepted samples/tiles. May contain scene texture. Observation **cannot** replace sensor S/O or predicted covariance. | `residualNoiseState.measuredPostDemosaic` |
| `relativeSpatialSigmaAvailable` | Existing compact tile-map relative sigma. Values are not copied into the context or recomputed. | `g_spatialNoiseMap.relativePhysicalShape` |
| `spatialShapeIncludesLscVariance` | Existing spatial variance-shape authority already contains mapped LSC gain squared if marked ready. Never multiply LSC again. | `g_spatialNoiseMap.lensShadingSquaredIncluded` |
| `fusionVarianceAlreadyIncluded` | True if neural posterior seed already represents fused residual; otherwise the existing physical fusion scaling remains owned by existing chroma/luma integration. | `neuralPosteriorSeedApplied` |

### Propagation ownership and non-duplication

The new `SingleFrameNoiseContext.h` uses the existing `RawSpatialNoiseCalibrationPolicy.h` for sensor-domain queries, `SpectraNoisePropagation.h` for existing propagated covariance and `SpectraNoiseCalibration.h` for observations. It does **not** call a second S/O resolver, regenerate a tile map, propagate AWB or CCM, or apply a second LSC, spatial exposure or fusion gain.

Current propagation (unchanged): normalized sensor S/O -> measured/predicted RAW-finalizer and LSC residual -> spatial exposure (as applied) -> existing demosaic predictor -> existing physical chroma -> existing AWB/CCM -> later effects. The context holds only the pre- and post-demosaic predicted residual states. A fully separate `RAW finalization physical variance field` per pixel does not exist here; the contract does not invent one.

### Read-only integration

Construct once immediately after `postDemosaic` and its observation are populated in `IspCore.cpp`, and append an `sf2NoiseContext` diagnostic block to the existing render debug string. The object is immutable. No new GPU dispatch, buffers, CPU RGB clone, readback, filter strength, push constant or pixel-stage decision is introduced.

The source contract compares `IspCore.cpp` against the phase-0 commit after removing exactly the two read-only additions and header include. Phase-0 native pixel identity tests remain enabled.

### Known limits

- Demosaic correlation and phase-specific spatial covariance are deliberately out of scope until Phase 2. `postDemosaic` still carries existing predictor uncertainty.
- The diagnostic `predictionConfidence` is the **existing** post-demosaic confidence, not a new confidence fit.
- This captures existing scalar and covariance evidence, not a per-pixel field; without RAW/same-input device runs it cannot prove photographic pixel identity.
- APK build and HONOR Magic 8 Pro comparisons have not been performed in this phase.
