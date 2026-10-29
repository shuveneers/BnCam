# BnC Auto continuous exposure transitions — 2026-10-10

Follow-up to BNC_AUTO_EXPOSURE_RECOVERY_2026-10-10.md. The user's video showed remaining brightness steps during scene changes and reported initial flicker settling after approximately two seconds. Later live feedback confirmed improved gradual transitions, with some remaining visible stepping. Video brightness cannot establish exact physical sensor settings or prove a flicker frequency.

## Implementation

Only the BnC Auto RAW controller opts into continuous transitions; Standard Android AE retains its existing route.

- The controller derives the endpoint from physically realized RAW exposure and radiance, then advances the last published product in logarithmic EV space on each fresh observed frame. Published settings never substitute for a physical metering observation.
- Normal slew is limited to 3 EV/s, with a 0.22–0.316-second exponential response approaching the endpoint. At 30 fps the normal trajectory increment is capped at approximately 0.10 EV before sensor allocation/quantization. Urgent clipping retains faster shortening. These are controller limits, not a guarantee that the HAL displays every submitted setting.
- A skipped observation cannot accumulate more than one frame of trajectory movement. The two-EV physical lead guard bounds a trajectory whose HAL settings fail to realize.
- Continuous submission accepts changes down to 0.005 EV instead of the former 0.20-EV hold. Small achievable ISO corrections can reach the sensor without accumulating into larger steps. Nanosecond cadence jitter cannot skip every other fresh frame.
- One control command may be queued. A bounded ledger validates earlier known physical requests while later trajectory points are in flight; an older frame cannot acknowledge its successor. Epoch, sensor identity and actual shutter/ISO remain required.
- The exposure-only request path reuses the configured stream and AF/AWB/OIS controls, while preserving request provenance, physical sensor keys and still-frame selection constraints. FPS capability negotiation and long diagnostic audits no longer run on every exposure tick. AF ground-truth recording and OIS enforcement remain active.
- When independent motion constraints permit an already known 50/60-Hz light period, acquisition selects the first safe aligned candidate without the previous unaligned-candidate utility/hysteresis delay. Real motion constraints still win. Unknown PWM is not assigned an arbitrary period.
- First physical realization latency is recorded once per submitted setting. Subsequent stable frames no longer inflate that diagnostic into seconds.

## Validation

Final focused run: **114 tests, zero failures**, including a five-frame delayed physical pipeline, normal physical increments below 0.11 EV in that simulation, convergence after a large scene change, dropped-frame limits, stalled HAL lead guard, request/RAW mismatches, single queued control, one-percent ISO tail corrections, cadence jitter, first-realization telemetry, 50/60-Hz acquisition, motion ceilings, allocation and RAW metering. Kotlin/native debug APK assembly succeeded. `git diff --check` passed.

Build log: work/bnc-auto-smooth-build-final.log. APK: app/build/outputs/apk/debug/app-debug.apk. Installed via ADB on Honor BKQ-N49, AUWE025B03006422, and restarted for runtime verification.

The earlier smooth development build measured median exposure-only control rebuild of approximately 0.98 ms (p95 3.97 ms), compared with approximately 42 ms for the earlier full control rebuild. Different sessions/scenes were used; this is diagnostic evidence, not a controlled benchmark. Its continuous-mode feedback-latency samples were inflated by the telemetry issue described above and must not be interpreted as HAL response time.

Final installed build, process 13465: 118 control rebuild samples, median 1.12 ms and p95 2.12 ms. Physical RAW results report AE OFF, aligned 30-ms shutter (actual 29,999,995 ns) and matching ISO within sensor quantization. Stable frames preserve the recorded first-realization latency instead of increasing it. The cadence dump reports 30.01 sensor fps and 29.72 actual display presentation fps for the sampled scene. Evidence: work/bnc-auto-smooth-final-runtime.txt and work/bnc-auto-smooth-telemetry.txt. This does not establish visually smooth brightness under the user's changing scenes.

The broader suite remains non-green as described in the earlier recovery note; no clean baseline was substituted for the user's working tree. These focused tests do not prove subjective equivalence with Standard AE, flicker elimination under the upstairs lamp, or performance across all lenses and lighting conditions. Practical visual confirmation remains the user's test.
