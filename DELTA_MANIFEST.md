# BnCam RayFocus Precision Autofocus Servo — Delta Manifest

**Date**: 2026-08-16
**Scope**: Complete replacement of previous tap-object/saliency focus logic with the **BnCam RayFocus Precision Autofocus Servo**.

---

## 1. Core Architecture Summary

RayFocus enforces an **immutable exact tap coordinate**, removing all saliency translation, object centroids, texture candidates, or connected-object selection.

1. **Pixel-Accurate Coordinate Truth Gate (`RayFocusCoordinateTruth.kt`)**:
   - Performs strict round-trip mapping:
     `Viewfinder (0..1) -> Analysis Pixel (px, py) -> Camera2 Sensor (sx, sy) -> Round-trip Pixel (rx, ry)`.
   - Tightened tolerance to **$\le 2.0$ pixels error** (`euclideanPixelErrorPx <= 2.0f`). Aborts RayFocus cleanly if error exceeds tolerance.

2. **Center Ownership Gate INSIDE Every Score Calculation (`RayFocusConcentricKernel.kt`)**:
   - Every diopter sample calculation $S(d)$ applies the `CenterOwnershipGate` **INSIDE** the concentric kernel scoring loop.
   - Isolated off-axis detail (e.g. tap in empty sky 3cm beside plant) is **REJECTED**.
   - Outer rings (3%, 6%) contribute ONLY if geometrically/radially connected to structure enclosing the immutable tap center.

3. **3-Point Safe Directional Acquisition**:
   - Initial acquisition measures $S(d_0 - \Delta d)$, $S(d_0)$, and $S(d_0 + \Delta d)$ with noise-aware epsilon $\epsilon$.
   - **Actuator Boundary Handling**:
     - Near Infinity ($d_0 \approx 0.0$): Asymmetric sampling $d_0, d_0 + \Delta d, d_0 + 2\Delta d$.
     - Near Macro Limit ($d_0 \approx d_{max}$): Asymmetric sampling $d_0 - 2\Delta d, d_0 - \Delta d, d_0$.

4. **Center-Locked Sparse Macro Recovery Scan**:
   - Scores 6 sparse diopter zones across $[0.0 .. d_{max}]$ against the **same immutable center-owned kernel**.
   - Reports `NO_RELIABLE_FOCUS_EVIDENCE` if no center-owned focus evidence exists anywhere in the range.

5. **Generic Settle Contract**:
   - Uses `LENS_STATE == STATIONARY` when available, with a stable $N=2$ frame fallback when `LENS_STATE` is absent.

6. **Capabilities Classification**:
   - `FIXED_FOCUS`: Sensor lacks focus motor $\rightarrow$ No RayFocus, no AF scan, report capability.
   - `AF_CAPABLE_BUT_MANUAL_CONTROL_UNUSABLE`: Camera2 AF capable but manual control unusable $\rightarrow$ Camera2 AF fallback.
   - `MANUAL_FOCUS_CAPABLE`: RayFocus eligible.

7. **Detailed Diagnostic Metrics Instrumentation**:
   - Logs complete diagnostic parameters:
     `numberOfLensMoves`, `totalTransactionMs`, `recoveryUsed`, `initialD0`, `measuredPositions`, `measuredScores`, `selectedBracket`, `finalFocusPosition`, `finalCenterScore`, `coordinateErrorPx`.

---

## 2. Files Modified & Created

- `app/src/main/java/com/bncam/core/capture/RayFocusPolicy.kt` `[NEW]`
- `app/src/main/java/com/bncam/core/capture/RayFocusCoordinateTruth.kt` `[NEW]`
- `app/src/main/java/com/bncam/core/capture/RayFocusConcentricKernel.kt` `[NEW]`
- `app/src/main/java/com/bncam/core/capture/RayFocusEngine.kt` `[NEW]`
- `app/src/main/java/com/bncam/core/capture/TapFocusTargetResolver.kt` `[MODIFY]`
- `app/src/main/java/com/bncam/core/quality/FocusConfidenceEngine.kt` `[MODIFY]`
- `app/src/main/java/com/bncam/core/capture/PerLensAdaptiveModel.kt` `[MODIFY]`
- `app/src/main/java/com/bncam/core/engine/BnCameraManager.kt` `[MODIFY]`
- `app/src/main/java/com/bncam/core/capture/PredictiveFocusSolver.kt` `[DEPRECATED]`
- `DELTA_MANIFEST.md` `[UPDATED]`
- `bncam_rayfocus_precision_servo_delta.zip` `[NEW ZIP]`

---

## 3. Device Validation Protocol (Execution Paused)

Execution **STOPS** after this build for device tuning & validation:
- **Coordinate Truth Audit**: Verify log output for `RayFocusCoordinateTruth`: `viewfinder tap -> analysis pixel -> Camera2 sensor -> round-trip pixel <= 2.0px PASS`.
- **Macro & Tele Photo Precision**:
  - Small subject macro tap (near focus limit).
  - Telephoto 3.7x precision tap on small detail.
  - Infinite to macro focus transition (testing center-locked macro recovery path).
  - Tap in empty sky 3cm beside plant (verify plant is REJECTED by Center Ownership Gate).
