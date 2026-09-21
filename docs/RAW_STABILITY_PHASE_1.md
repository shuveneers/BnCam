# Phase 1 — Cold RAW single-frame capture

Baseline verified before editing: branch `main`, clean worktree, HEAD
`daf9b5a2f3d50572917a3d1ae0b06dcaa3e54f31` (`awb presets`). No history changes,
commit or push. Only Phase 1 is implemented. Phases 2–6 require a new instruction.

## Baseline architecture audit

| Area | Actual implementation inspected |
| --- | --- |
| RAW10 and RAW_SENSOR | Both use the canonical, full capture-resolution RAW ImageReader as a warm producer. RAW_SENSOR is not inherently a cold-only session. |
| Reader/session | BnCameraManager creates the canonical reader before configuring the session; session callbacks capture reader identity for retirement. |
| Repeating targets | Canonical ring surface, attached display surface, and an optional separately configured display-only custom RAW reader. |
| Still targets | Existing flash/HDR requests target the already configured canonical reader surface. |
| Image ownership | FrameRingBuffer owns the open Image and its HardwareBuffer wrapper after accepted transfer. FrameLease pins a pair against recycling; close is deferred while leased. |
| Warm pairing | Canonical ring pairs by timestamp and generation, requires exact request provenance and valid sensor authority for RAW. The separate WarmBufferPairingCoordinator class is not used by the active manager path. |
| Warm SINGLE | A pre-shutter candidate is pinned at admission. Previously the runner independently collected/scored ring candidates again. |
| Cold SINGLE | No dedicated non-flash RAW still fallback. The route could spend up to 4.2 seconds in readiness, then wait for repeating frames and perform a repeating recovery kick. A first post-shutter repeating frame could receive an adjusted eligibility boundary. |
| Prewarm | RawStillWorkingSetPrewarmer is requested asynchronously, including from producer setup/first Image. No new synchronous prewarm is added. |
| Preview input | Renderer retains a native AHardwareBuffer inside the scoped ring borrow before returning; it does not take ownership of the capture Image. |
| Vulkan transport | Direct VkBuffer import is restricted to legal BLOB/GPU_DATA_BUFFER layout. Ordinary camera RAW uses lock/lockPlanes and memcpy into reused staging; a device copy may follow. No claim of universal zero-copy. |
| Vulkan completion | Submission is followed by a fence wait with a 20 ms completion bound. Existing output slots do not make the whole mutable compute working set independently asynchronous. |
| GLES output | Kotlin output-slot ownership distinguishes publication and downstream GL fence completion; GPU buffers can be quarantined on interop failure. Vulkan completion alone is not the GL release signal. |
| Pending preview | Bounded FIFO with two pending requests; overflow removes the oldest. This is not strict newest-only scheduling. |
| WB/CCM | Timestamp-aware paired color metadata is retained; system-auto temporal rendering and manual WB have separate policies. No color policy is changed. |
| Black/white | Values are supplied via the renderer configuration and source-domain transforms, not a newly resolved exact dynamic pair for every displayed Image. |
| Lens shading | Camera2 lens-shading map reporting is requested when available, and sensor metadata can carry it. The inspected RAW preview configuration/shader does not consume a matching per-frame shading map. |
| Resolution/quality | RawPreviewResolutionPolicy keeps BALANCED at a 1440×1080 ceiling. SHARP remains inactive. Shaders, tone, denoise, AWB and demosaic are unchanged. |

Preview transport, compute serialization, FIFO latency, calibration association and full
GPU/GL retirement correctness remain later-phase work. This inspection is not an on-device
proof of producer-buffer reuse safety or every Vulkan error-path lifetime.

## Root cause and implementation

Warm-ring readiness was being used as permission to take a SINGLE RAW photo. A configured
Camera2 session can accept a still without any eligible pre-shutter ring pair.

RAW SINGLE now validates the existing producer identity, format, reader, session and
configured generation without waiting for warm-buffer/3A readiness. It fails if that producer
is unavailable; it does not rebuild it from this shutter route.

- An eligible pinned warm RAW frame is passed directly into the runner's collector and kept
  leased through dispatch. Diagnostic: `RAW_SINGLE_SOURCE=NEAR_ZSL_PRE_SHUTTER`.
- Otherwise one `TEMPLATE_STILL_CAPTURE` request targets the existing canonical RAW surface,
  with current metering, exposure, WB and focus policies. Diagnostic:
  `RAW_SINGLE_SOURCE=COLD_DIRECT_STILL`.
- Its own callback supplies exact request provenance and physical sensor metadata to the same
  ring. Acquisition requires the requested timestamp, generation, epoch and format, plus the
  existing RAW sensor-authority validation. No neighboring repeating frame can substitute.
- The runner receives the reserved lease and enforces the explicit post-shutter still contract.
  The actual user shutter timestamp stays unchanged. Cold stills use their own WB metadata.
- Acquisition observes ring lifecycle events and shutdown cancellation. Late/duplicate result
  callbacks are ignored after the result or job completes. Ring waits are event-driven rather
  than 30 ms polling. The bounded event history prevents a clear hidden by a newer frame event
  from being missed.
- The outer capture scope releases its lease in `finally`; ownership is recorded before the
  cancellable coroutine return. The runner takes its own lease before processing. No new Image
  or AHB ownership pool is introduced.

The existing still-delivery failure limits (2.5 seconds for result, 1.6 seconds for the exact
pair) are reused as failure bounds only. Success continues immediately on delivery. These are
not warm-up waits, retries or a guarantee that a faulty HAL will respond quickly.

Multi-frame/HDR/flash acquisition and YUV selection semantics are unchanged. The existing
Near-ZSL repeating recovery helper remains reachable for the existing non-RAW route only.

## Files changed

- `app/src/main/java/com/bncam/core/engine/BnCameraManager.kt`
- `app/src/main/java/com/bncam/core/buffer/FrameRingBuffer.kt`
- `app/src/main/java/com/bncam/core/capture/ShutterCandidateCollector.kt`
- `app/src/main/java/com/bncam/core/runners/SingleFrameRunner.kt`
- `app/src/test/java/com/bncam/core/buffer/ColdRawSinglePairingTest.kt` (new)
- `app/src/test/java/com/bncam/core/engine/ColdRawSingleCaptureContractTest.kt` (new)
- `docs/RAW_STABILITY_PHASE_1.md` (this report)

Source diff: four existing production files, 200 insertions and 13 deletions; two new test
files and this report are additional untracked files, so plain `git diff --stat` excludes them.
The generation-change log uses the ring's existing SafeLog wrapper, allowing actual lifecycle
events to be exercised in local JVM tests without Android Log stubs throwing first.

## Tests and validation

All commands ran from the repository root with
`$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'`.

| Command | Result |
| --- | --- |
| `git status`, `git rev-parse HEAD`, `git log --oneline --decorate -10` | Expected baseline verified. |
| `.\gradlew.bat testDebugUnitTest --console=plain` | Initial run: 1,900 tests, 364 failures. Kotlin compilation succeeded. |
| `.\gradlew.bat testDebugUnitTest --tests '*ColdRawSingle*' --console=plain` | Passed all 11 new tests present at that point. |
| `.\gradlew.bat testDebugUnitTest assembleDebug --continue --console=plain` | Final run: 1,912 tests, 354 failures. All 12 new tests passed. APK/native tasks succeeded; combined command fails because the test suite fails. |
| `.\gradlew.bat assembleDebug --console=plain` | Separate final build succeeded, including configured arm64-v8a native build dependencies. |
| `git diff --check`, `git diff --stat`, `git diff`, `git status` | Reviewed. No whitespace errors. |

Seven new behavioral tests exercise the real ring waiter/leases with fixture Android payloads
and simulated arrival events: both RAW formats, immediate warm pair, both arrival orders,
duplicate completion events, wrong timestamp/epoch/format, generation switch, clear/shutdown
signal, cancellation, stale sensor metadata and a clear followed immediately by a pair event.
Five source-contract tests cover Camera2 wiring, producer reuse, RAW SINGLE scope, diagnostics,
callback cancellation/duplicate guards and reserved-frame dispatch. These do not instantiate
a real CameraCaptureSession or emulate ImageReader/HAL callbacks.

The full suite is **not green**. Examples verified against unchanged baseline code include
the flash test expecting an inline `dedicatedFlashStill -> CaptureStrategy.SINGLE_FRAME_ZSL`
branch that is absent at HEAD, and Android Log stubs preventing the original ring lifecycle
tests from executing. Other failures include absent source files and stale preview/shader
contracts outside this diff. Not all 354 failures have been individually baseline-reproduced.
No tests were disabled and no unrelated expected behavior was rewritten.

Evidence: `work/phase1-validation-final.log`, `work/phase1-build-final.log`, and
`app/build/reports/tests/testDebugUnitTest/index.html`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## On-device validation and remaining risks

1. For RAW10 and RAW_SENSOR, open the camera and press SINGLE immediately, before the first
   eligible warm pair. Expect one `COLD_RAW_SINGLE` submission and `COLD_DIRECT_STILL`, without
   a readiness warm-up/repeating recovery delay. Verify the saved photo and exact timestamps.
2. After the ring warms, press SINGLE again. Expect `NEAR_ZSL_PRE_SHUTTER` and no dedicated
   cold still submission. Check the selected moment and normal photo output.
3. Check session-generation, session-create and ImageReader diagnostics during cold capture:
   none should change solely because the ring is empty. Test both YUV and RAW viewfinders.
4. Switch lens/source, leave the camera, or shut down during a cold capture. It must cancel
   or fail cleanly, release ownership and allow the next capture without stale-frame output.
5. Exercise fast repeated taps and a deliberately slow exposure. Verify capture admission,
   no persistent busy state and no duplicate photo. Test existing flash/HDR/multi-frame routes
   for regressions without expecting the cold SINGLE fallback to apply to them.
6. Check that missing/failed RAW preview rendering does not prevent the cold RAW still request.

Real still-request support on the active topology, startup HAL timing, physical-camera result
delivery, actual Image/HardwareBuffer recycling and callback races require device evidence.
No phone test or APK installation was performed. The full-suite failures remain an explicit
validation limitation. Stop here; do not start Phase 2 without instruction.
