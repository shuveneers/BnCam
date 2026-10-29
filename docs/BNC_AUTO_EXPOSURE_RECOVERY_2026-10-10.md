# BnC Auto exposure recovery — 10 October 2026

## Concrete causes and repairs

- AE ran inside the oldest-first focus-analysis drain. Sensor FPS therefore did not describe feedback latency. BnC Auto now has a separate conflated worker which leases the latest complete, proven RAW/result pair; focus analysis keeps its original ordered behavior. Images remain leased throughout analysis.
- Delta pacing registered pending exposure before the control queue submitted it, acknowledged numerical shutter/ISO without checking request provenance, and could compound corrections from an unimplemented target. The new feedback gate reserves one queued target, starts the HAL deadline only after successful setRepeatingRequest, requires a current request epoch and physical RAW/result settings, rejects backwards timestamps, and rebases from actual settings after bounded HAL mismatch. AF requests with unchanged exposure do not renew the deadline. Queue failure restores the previous target; retiring callbacks cannot reset the new sensor gate. Sensor and host clocks remain separate.
- The controller now bounds EV correction against the exposure product which generated the RAW measurement. Still targets can exceed the warm-stream budget only when physical warm preview is saturated. Submission decisions compare warm shutter/ISO, so still-target changes cannot keep rebuilding identical preview settings.
- A clipped bright window/sky could become an excluded point source immediately after shortening exposure, lift the highlight limit, and double gain until clipping returned. A physical highlight-radiance limit persists across this classification change and is retired on a significant measured scene-radiance change.
- An odd five-sample radiance median could alternate with the majority phase of periodic light pulses. The central interval of an even window now combines both phases in log-radiance space. Two agreeing large scene innovations retire the history, while broad clipping bypasses it immediately.
- The legacy RAW motion publisher continued issuing repeating updates alongside BnC Auto. Under BnC Auto it now only publishes evidence; its analysis uses the same fresh analysis lane rather than the focus backlog.
- The candidate score could reject a validated 10/8.33 ms light period for lacking permission for a long tripod exposure. Complete validated periods within the preview and motion budgets are eligible; scoring now includes the physical residual sinusoidal response of a box exposure. Motion and sensor-product bounds still take precedence. Realized 9,999,993 ns is correctly classified as aligned with 10 ms using the existing tolerance.
- Strong uniform PWM pulses beyond the old scalar-gain range can pass motion exclusion only with the same wide spatial coverage and noise-qualified residual proof. Local subject changes during a pulse remain rejected. Missing physical sensor metadata is no longer replaced by logical metadata in the BnC Auto meter/realization paths.

BnC Auto remains under CONTROL_AE_MODE_OFF. No HAL exposure reference, frame-rate cap, ISP/demosaic change, multi-frame capture or Standard Auto controller change was introduced. The local Delta01–07 changes were retained. No reset, clean, commit, push or delta archive was performed.

## Files changed by this repair

Production:
- app/src/main/java/com/bncam/core/capture/BnAutoExposureEngine.kt
- app/src/main/java/com/bncam/core/capture/BnAutoControlPacing.kt
- app/src/main/java/com/bncam/core/capture/BnAutoFeedbackGate.kt (new)
- app/src/main/java/com/bncam/core/capture/RawIlluminationChangePolicy.kt
- app/src/main/java/com/bncam/core/engine/BnCameraManager.kt
- app/src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt

Tests:
- BnAutoFeedbackGateTest.kt (new), BnAutoRecoveryTest.kt (new)
- BnAutoExposureEngineTest.kt, BnAutoPublishedTargetTest.kt
- BnAutoControlPacingTest.kt, RawIlluminationChangePolicyTest.kt

Other dirty/untracked files shown by git status were already present at the start, including RawFlickerStabilityTracker and its tests.

## Verification

- Final targeted run: **116 tests, zero failures**. Covers request/epoch realization, host/sensor clock separation, HAL clamp timeout, queue cancellation, lens changes, AF deadline independence, a simulated two-frame HAL pipeline, broad-window clipping cycles, alternating LED phases, fast scene-step history retirement, validated 50 Hz shutter selection, 50/60 Hz timing arithmetic, local motion during PWM pulses, RAW10/RAW_SENSOR metering and motion sampling, exposure allocation and exposure authority.
- Kotlin compilation, native CMake builds, debug APK assembly and installation succeeded. APK: app/build/outputs/apk/debug/app-debug.apk.
- git diff --check passed.
- Wider suite was also run: **2035 tests, 354 failures** on the run before the final alternating-phase regression test was added. It is not green; many failures are older source-contract assertions concerning routes/UI outside this repair. This is not evidence that all failures predate this work; no clean baseline was substituted for the user's local working tree. See work/bnc-auto-recovery-full-final.log. Targeted tests and the APK were subsequently rebuilt successfully; see work/bnc-auto-recovery-targeted-final.log.

## Hardware evidence and limitations

ADB device: Honor BKQ-N49 / AUWE025B03006422. RAW_SENSOR was exercised, including physical sensor 5 initially and sensor 2 for the later test. Requests/results show AE mode OFF and matching physical exposure/ISO/frame duration.

A development build initially exposed repeated gain half/double cycling and duplicate submissions on sensor 2. This was observed, reported, and repaired before the final APK was delivered.

After the highlight and legacy-publisher repairs, the initial stationary sensor-2 window from 09:26:45 to 09:27:15 had **zero repeating submissions**; the sensor ran at **30.0075 fps** and actual display presentation at **29.9347 fps** in the corresponding cadence dump. This measures the tested scene, not all lighting conditions. The final APK additionally contains the tested PWM gain-range, cancellation and even-window filtering fixes.

Across that repair build's recorded session: median analysis queue 0.326 ms (p95 0.708), RAW/motion analysis 21.132 ms (p95 28.729), controller 1.503 ms (p95 2.166), control queue 0.109 ms (p95 0.454), total control rebuild 42.351 ms (p95 47.194), setRepeatingRequest call 0.611 ms (p95 0.926), submission to usable physical feedback 172.817 ms (p95 205.700). Repeated sampled latency values are included; these are diagnostic distributions, not a controlled benchmark of unique transactions. Sensor-to-present latency was about 149 ms median in the stationary cadence dump.

An ADB tap produced TOUCH_AF_CANCEL at 09:27:20.148 and TOUCH_AF_CONTINUOUS_REPEATING at 09:27:20.172 (24 ms between the logged submissions), with the requested AF region present. This demonstrates AF submissions, not optical focus-lock latency or subjective focus quality.

Trace fields distinguish RAW arrival, result arrival, analysis queue, metering, decision, control queue, Camera2 submission and physical feedback. Sensor-to-RAW latency is computed only when timestamp-source metadata confirms comparable clocks. Existing RAW cadence diagnostics separately report sensor-to-Vulkan/draw/display latency. Logs are sampled once per second for the meter; submission events occur only on actual control submissions.

**Unverified:** the user's problematic upstairs lamp was unavailable for this session; no claim of flicker elimination under that lamp or controlled hardware convergence timing is made. Unknown PWM frequency stays unknown; whole-frame brightness sampled near 30 fps cannot uniquely identify arbitrary high-frequency lighting. Rolling-shutter banding, manual-mode scene-flicker reliability, other sensor/lens pipelines, 60 Hz lighting, RAW10 on hardware, DNG/still image quality and physical AF lock still need the user's practical test. No new arbitrary frequency is inferred from metadata gaps. Standard Auto remains the unchanged HAL-AE route.

Runtime evidence: work/bnc-auto-runtime-final-extract.txt, work/bnc-auto-runtime-stable-final.txt, work/bnc-auto-runtime-latest.txt, work/bnc-auto-focus-runtime.txt. These logs contain evidence from successive development builds; use process IDs and the times above to identify the measured repair build.
