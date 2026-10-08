# Main RAW10 / RAW_SENSOR scene-E comparison

2026-10-08. Camera 2, 4096×3072 canonical RAW, approximately 30 ms exposure. Three valid physical RAW10 inputs and three subsequent RAW_SENSOR inputs, each replayed twice per algorithm through Malvar, AMaZE and Hybrid. All pairs are bitexact, all requested algorithms executed without fallback, and all six actual publications have exactly the selected replay's decoded pixels. Canonical RAW and output checksums are preserved per input.

| Property | RAW10 | Subsequent RAW_SENSOR |
| --- | --- | --- |
| Collection under `work/quality/` | `main-raw10-E-physical` | `main-rawsensor-E-format-pair-retry` |
| ISO | 324 / 314 / 314 | 304 / 293 / 273 |
| Focus distance (diopters) | 4.630 / 4.310 / 4.310 | 4.237 / 4.115 / 4.405 |
| Malvar/AMaZE mean absolute encoded RGB disagreement | .00804–.00835 | .00872–.00908 |
| Ordinary latency, replay disabled | 2.50–2.79 s | 2.57–2.90 s |

The source switch changed the recipe source and source-dependent warm-buffer capacity (RAW_SENSOR 15, RAW10 effective 25). ISP/image controls otherwise compare equal after excluding runtime identity/timestamp/thermal/capability records. No settings were normalized or edited. Inputs from different formats are independent physical sensor samples, never identical RAW.

The same objects are present, but framing visibly changed. AF and ISO also changed. Regions are independently declared in `physical-E-raw10-rois.json` and `physical-E-format-pair-rois.json`, not registered pixel pairs. Metrics, differences and 100%/200% crops are in each collection's `manual-roi-analysis`; scorecards are `work/quality/E-raw10-scorecard.json` and `E-rawsensor-format-pair-scorecard.json`. Disagreement ranges do not establish which source has better color/detail/noise. Household objects do not support absolute color accuracy.

RAW10 isolated GPU center readback: 257×193, weights .143597–.856403, NaN/Inf/out-of-range/unwritten zero, complement error 7.45058e-8, candidate error zero, oracle error 7.18778e-8, three bitexact replays. The existing native numerical suite passes. This is separate diagnostic evidence: crop content/priors differ from RAW_SENSOR, and it is not a production full-frame mask comparison.

Ordinary latency retained JPEG_PLUS_RAW and qualification checksum overhead. RAW10 thermal status was 1 throughout; RAW_SENSOR was 1 for two samples and 2 for the third. Overlapping ranges do not support a controlled source speed ranking. See LATENCY.md for stage costs and the >8-second instrumentation cause.

One initial RAW_SENSOR diagnostic request hit `NO_ACTIVE_PIPELINE`, zero buffered frames, generation 5; its failed terminal is preserved separately. Later logs show active preview in generation 6 and three successful retry frames followed. Exact transition cause is unproven; no product fix was made.

This establishes targeted E source coverage. Planned representative RAW10 A/B/D coverage and production-stage noise/detail attribution remain incomplete. No RAW ingestion, demosaic, color, noise or tone correctness defect is established here; unresolved rendition differences retain classification UNKNOWN.
