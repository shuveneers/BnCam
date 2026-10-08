# Processing time investigation — 2026-10-08

## Reproduced cause of the >8 second RAW10 measurement

The opt-in `QualityCaptureReplay` helper introduced for this quality study runs **six extra full JPEG renders** synchronously before the ordinary render: two deterministic replays each of Malvar, AMaZE and Hybrid. It also exports canonical RAW and the six JPEGs. In `SingleFrameRunner`, this call occurs after `renderStartMs` and `nativeProcessingStart()`. Consequently the existing render/processing/elapsed clocks include the diagnostic work. The invocation counters intentionally describe the ordinary production render, so their value of one must not be interpreted as the total diagnostic workload when `qualityReplayPath` is present.

This is measurement contamination from our diagnostic instrumentation, not evidence that an ordinary capture requires seven renders. The disabled-by-default helper is guarded by DEBUG and a non-null request; the collector clears the request in its finally block. It has now been explicitly cleared before the ordinary latency series. No image or pipeline settings were changed to obtain the timings below.

Observed RAW10-E diagnostic captures include 8.55–8.94 seconds total in the first two samples, with 8.00–8.23 seconds in the render block; the ordinary native render within those captures is 1.07–1.09 seconds. Earlier RAW_SENSOR-E diagnostic captures totaled 19.98–20.39 seconds, including 18.67–19.05 seconds in the render block. Those diagnostic times are not ordinary shutter latency or comparable thermal benchmarks.

## Ordinary live captures, replay disabled

Reproducible command: `scripts/quality_latency.py --serial AUWE025B03006422 --source RAW10 --camera 2 --output work/quality/main-raw10-E-ordinary-latency` (numpy/Pillow runtime is unnecessary for this script). The existing qualification evidence checks remain enabled, including published JPEG checksums. Detailed records and thermal dumps are in that ignored directory.

| Sequential stage (ms) | Capture 4 | Capture 5 | Capture 6 |
| --- | ---: | ---: | ---: |
| Select frame | 11.02 | 11.57 | 11.20 |
| RAW unpack/input stage | 279.51 | 276.91 | 281.13 |
| JPEG render block | 1717.07 | 1961.18 | 1694.67 |
| JPEG save | 105.05 | 95.63 | 85.47 |
| DNG write | 325.15 | 317.12 | 304.45 |
| Publication completion | 124.59 | 120.97 | 121.70 |
| Total reported elapsed | **2564.60** | **2785.27** | **2500.41** |

All three have one ordinary ISP invocation, one JPEG encode, one JPEG publication, one DNG publication and one terminal publication. The recipe uses JPEG_PLUS_RAW; the DNG time is part of the selected output policy. No DNG/output policy was disabled for this measurement.

Native outer render times are 1602.51 / 1767.02 / 1587.64 ms, nested inside the JPEG render block. RAW unpack, native processing, JPEG encoding and nested Spectra/tone timings overlap their enclosing stage and must not be summed as additional costs. Qualification RAW evidence takes approximately 19.8–20.2 ms, output evidence 11.0–11.6 ms; both are also nested overhead, not an independent stage to add again.

Thermal status was **1 before and after all three ordinary captures**, with live preview active. These are observed live timings, not a status-0 qualified benchmark. The different native timings within diagnostic versus ordinary captures cannot be attributed to source format alone: scheduling, warm state and thermal conditions were not held constant. The subsequent ordinary RAW_SENSOR comparison is recorded below.

## Outcome and remaining work

- The >8 second RAW10 result is reproducibly explained by diagnostic replay. Ordinary measured RAW10 capture latency is 2.50–2.79 seconds (median 2.56 seconds), with the diagnostic request explicitly absent.
- This does not establish that 2.56 seconds meets the product latency target. The render block is the largest remaining measured component; JPEG_PLUS_RAW storage and publication also contribute.
- Ordinary RAW_SENSOR totals: 2657.86 / 2570.98 / 2904.01 ms. The first two have thermal status 1 before/after; the third has status 2 before/after. RAW10 totals are 2500.41–2785.27 ms at status 1. These small sequential samples overlap and do not support a clean source-format speed ranking. Both formats demonstrably complete ordinary captures below 3 seconds in these samples.
- No production performance optimization, shader math change, image-quality change or architectural redesign was made. No product correctness fix has been justified by these measurements.
- Future performance reports must exclude rows carrying `qualityReplayPath` or label them as diagnostic workload. The complete device gate previously passed with this measurement APK; subsequent changes so far are host tools and documentation only.

One subsequent diagnostic capture was refused as `pipeline_not_ready:NO_ACTIVE_PIPELINE expected=RAW_SENSOR profile=2_profile_1`, generation 5, zero ring-buffer frames, while the app was foreground. A later read-only log sample showed active RAW_SENSOR preview in generation 6. The failed terminal was preserved under `work/quality/main-rawsensor-E-format-pair/`; the exact transition cause is not established. Retry evidence is kept separately. This is not counted as a successful quality frame.
