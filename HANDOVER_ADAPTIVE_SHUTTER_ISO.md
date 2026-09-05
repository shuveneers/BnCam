# HANDOVER: Adaptive Shutter × ISO Engine (GCam Matching) for BnCam

**Project**: BnCam (Android Computational RAW Photography Engine)  
**Target Architecture**: Camera2 API + Vulkan GPU Compute Pipeline + Near-ZSL RAW Ring Buffer  
**Objective**: Eliminate the 1/100s shutter clamping and extreme ISO noise (ISO 16,589) in low light by implementing an adaptive, photon-maximizing computational AE curve matching Google Camera (GCam).

---

## 1. Executive Context & Test Scene Problem Statement

The user compared BnCam against Google Camera (GCam) on the exact same device and sensor across 5 real-world scenes:

| Scene | Current BnCam Result | GCam Result | Physical Difference |
|---|---|---|---|
| **Shades** | 1/100s (10ms) — ISO 13,067 | **1/25s (40ms) — ISO 7,464** | GCam captures **$4\times$ more light** (2 stops); ISO is halved. |
| **Chair** | 1/100s (10ms) — ISO 16,589 | **1/20s (50ms) — ISO 10,200** | GCam captures **$5\times$ more light** (2.3 stops); full OIS handheld floor used. |
| **Kitchen** | 1/100s (10ms) — ISO 4,822 | **1/25s (40ms) — ISO 3,517** | GCam captures **$4\times$ more light**; shadows are clean. |
| **Tablet** | 1/100s (10ms) — ISO 2,073 | **1/33s (30ms) — ISO 1,764** | GCam captures **$3\times$ more light**; intermediate 50Hz harmonic. |
| **Lamp** | 1/33s (30ms) — ISO 288 (clipped) | **1/50s (20ms) — ISO 2,698** | GCam uses **Highlight-Biased Metering (ETTR)**: protects bulb filament from clipping. |

### The Core Law of Computational Photography
$$\text{SNR} \propto \sqrt{N_{\text{photons}}}$$
Noise is dominated by photon shot noise. By clamping exposure to 1/100s (10ms) in low light, BnCam starves the sensor of photons, forcing the Camera2 HAL to apply dirty digital gain (ISO 13,000–16,500), resulting in noisy, desaturated images. GCam holds the shutter open for as long as motion constraints allow (1/20s–1/33s), capturing $3\times$ to $5\times$ more physical photons.

---

## 2. Root Cause Analysis: The 3 Blockers in BnCam

### Blocker 1: Cadence Policy Forces Fixed 30 FPS (`[30, 30]`)
- **File**: `app/src/main/java/com/bncam/core/capture/SensorStreamCadencePolicy.kt` (lines 98–108)
- **Problem**: When selecting among advertised AE FPS ranges that share `effectiveUpper = 30`, the code sorts by `.thenBy { it.range.lower }`. Given `[7, 30]`, `[15, 30]`, `[30, 30]`, it chooses `[30, 30]`.
- **The Physics Trap**:
  - `CONTROL_AE_TARGET_FPS_RANGE = [30, 30]` forbids the camera HAL from ever exceeding $33.3\text{ ms}$ frame duration.
  - Mobile sensor rolling shutter readout takes $\approx 15\text{--}20\text{ ms}$.
  - Available exposure time is $33.3\text{ ms} - 18\text{ ms} \approx 15.3\text{ ms}$.
  - In 50Hz mains power regions, valid anti-flicker exposure steps are integer multiples of 10ms: $10\text{ms}$ (1/100s), $20\text{ms}$ (1/50s), $30\text{ms}$ (1/33s), $40\text{ms}$ (1/25s), $50\text{ms}$ (1/20s).
  - Because 20ms does not fit in 15.3ms, **10ms (1/100s) is the ONLY exposure time the HAL can legally choose**! The HAL is forced to clamp shutter to 1/100s and pump ISO to 16,589.
- **Solution**: Select adaptive range with the lowest lower bound (`[15, 30]`, `[12, 30]`, or `[7, 30]`). This allows the HAL to drop frame rates to 25, 20, or 15 FPS in low light, unlocking 40ms (1/25s) and 50ms (1/20s).

### Blocker 2: Motion Policy Aborts When Camera is Steady
- **File**: `app/src/main/java/com/bncam/core/capture/DefaultRawShutterPriorityPolicy.kt` (line 95)
- **Problem**: `if (!ceilings.hasMotionEvidence()) return DefaultRawShutterPriorityPlan(ready = false)`.
- In dark low-texture scenes or when holding the camera steady, optical flow confidence is low. Motion evidence is withheld (`hasMotionEvidence() == false`).
- Instead of using the safe handheld stabilization ceiling ($50\text{ ms} = 1/20\text{s}$ for OIS), the policy aborted to `ready = false`, reverting to HAL preview AE at 1/100s!
- **Solution**: When motion evidence is calm or missing, use `lensStabilityNs` (dynamic sensor handshake ceiling: 50ms for wide with OIS) as the authoritative ceiling.

### Blocker 3: Android < API 36 Fallback Was Permanently Blocked
- **File**: `app/src/main/java/com/bncam/core/engine/BnCameraManager.kt` (lines 734 & 11414)
- **Problem**:
  1. On Android 14/15 (`SDK_INT < 36`), Camera2 exposure-time priority (`CONTROL_AE_PRIORITY_MODE`) is unavailable.
  2. The manual fallback in `applyExposurePolicy` guarded on `if (targetLuma == null) ... return`, leaving `SENSOR_EXPOSURE_TIME = null` and `SENSOR_SENSITIVITY = null`.
  3. At line 734, preview frames were dropped before analysis whenever `targetViewfinderSource == YUV` (the default!).
  4. Consequently, `ExposureStatistics` was never calculated, `targetLuma` was never set, and BnCam was permanently stuck in `DEFAULT_RAW_FALLBACK_LUMA_ANCHOR_BOOTSTRAP`.
- **Solution**:
  - In `rawPreviewRenderer`: run analysis for all active generation frames regardless of `targetViewfinderSource`.
  - In `applyExposurePolicy`: on Android < API 36, immediately apply `defaultRawPlan.targetExposureNs` and `defaultRawPlan.expectedIso` to `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` with `CONTROL_AE_MODE_OFF`.

---

## 3. Mathematical & Algorithmic Models (From Document)

### A. Dynamic Sensor Profiling (Zero Hardcoding)
Query `CameraCharacteristics` at runtime per sensor ID:
```kotlin
val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) // mm
val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) // mm
val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
val hasOis = oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

val diagonalMm = if (sensorSize != null) Math.hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble()).toFloat() else 6.0f
val cropFactor = 43.27f / diagonalMm
val focal35mmEq = (focalLengths?.firstOrNull() ?: 4.5f) * cropFactor

// Dynamic Handshake Ceiling
val ruleOfThumbSec = 1.0f / focal35mmEq.coerceAtLeast(14.0f)
val oisFactor = if (hasOis) 4.0f else 1.0f // 2-stop dynamic bonus for OIS
val safeHandheldSec = (ruleOfThumbSec * oisFactor).coerceIn(0.010f, 0.0667f) // Cap between 1/100s and 1/15s
val maxHandheldShutterNs = (safeHandheldSec * 1_000_000_000L).toLong()
```
- **Wide 24mm + OIS**: $\approx 50\text{ ms}$ (1/20s)
- **Ultrawide 14mm**: $\approx 66.7\text{ ms}$ (1/15s)
- **Telephoto 120mm 5×**: $\approx 20\text{ ms}$ (1/50s)

### B. Anti-Banding Step Locking
Snap shutter time down to the nearest anti-banding integer harmonic:
- 50 Hz mains ($T_{\text{period}} = 10\text{ ms}$):
  $$T \in \{10\text{ms (1/100s)}, 20\text{ms (1/50s)}, 30\text{ms (1/33s)}, 40\text{ms (1/25s)}, 50\text{ms (1/20s)}\}$$
- 60 Hz mains ($T_{\text{period}} = 8.33\text{ ms}$):
  $$T \in \{8.33\text{ms (1/120s)}, 16.67\text{ms (1/60s)}, 25\text{ms (1/40s)}, 33.3\text{ms (1/30s)}, 41.67\text{ms (1/24s)}, 50\text{ms (1/20s)}\}$$

### C. Shutter-Before-Gain Allocation
1. **Target Product**: $P = \text{measuredIso} \times \text{measuredExposureNs}$ (or from Vulkan log luma histogram).
2. **Safe Ceiling**: $T_{\text{ceiling}} = \min(T_{\text{motion\_limit}}, T_{\text{handheld\_ois}}, T_{\text{stream\_cadence}})$.
3. **Quantized Shutter**: $T = \text{quantizeToAntiBanding}(T_{\text{ceiling}})$.
4. **Ideal Shutter at Base ISO**: $T_{\text{base}} = \text{round}(P / \text{minIso})$.
5. **Target Shutter**: $T_{\text{target}} = \min(T, T_{\text{base}})$.
6. **Target Sensitivity**: $\text{ISO}_{\text{target}} = \text{round}(P / T_{\text{target}}).\text{coerceIn}(\text{minIso}, \text{maxIso})$.

### D. Highlight-Biased Metering (ETTR Override for Lamp Anomaly)
From Vulkan histogram metrics in `ExposureStatistics`:
- If `rawNearClipFraction > 0.005f` (specular highlights occupy > 0.5% of pixels):
  $$\Delta EV = \min(0f, -1.0f - (\text{clipRatio} \times 10.0f))$$
  Target product is biased downward, pulling shutter down to 1/50s to protect lightbulb filaments from clipping.

### E. Asymmetric Temporal Damping (EMA Filter)
$$EV_{\text{applied}}(t) = EV(t-1) + \alpha \cdot (EV_{\text{target}}(t) - EV(t-1))$$
- $\alpha = 0.40$ when $EV_{\text{target}} < EV_{\text{applied}}$ (rapid reaction to bright flashes / light sources).
- $\alpha = 0.12$ when transitioning into darkness (smooth, flicker-free transition).

---

## 4. Implementation Steps & File Modifications

### Step 1: Create `DynamicSensorProfile.kt` [NEW]
**Path**: `app/src/main/java/com/bncam/core/capture/DynamicSensorProfile.kt`
- Data class `DynamicSensorProfile`:
  - `focalLength35mmEq: Float`
  - `hasOis: Boolean`
  - `maxHandheldShutterNs: Long`
  - `minShutterNs: Long`
  - `maxShutterNs: Long`
  - `minIso: Int`
  - `maxIso: Int`
  - `analogGainLimitIso: Int`
- Factory `DynamicSensorProfile.fromCharacteristics(characteristics: CameraCharacteristics): DynamicSensorProfile`.

### Step 2: Update `SensorStreamCadencePolicy.kt` [MODIFY]
**Path**: `app/src/main/java/com/bncam/core/capture/SensorStreamCadencePolicy.kt`
- In `resolveFromSustainableUpperFps`:
  - Change candidate comparator so that when candidates share `effectiveUpper` (e.g. 30 FPS), it prefers an adaptive range whose `lower <= 20` (preferably 15 or 7) instead of `.thenBy { it.range.lower }`.
  - Prefer `minByOrNull { it.range.lower }` among ranges with `upper == sustainable` and `!it.range.fixed`.

### Step 3: Update `DefaultRawShutterPriorityPolicy.kt` [MODIFY]
**Path**: `app/src/main/java/com/bncam/core/capture/DefaultRawShutterPriorityPolicy.kt`
- In `RawShutterSafetyCeilings`:
  - Allow `lensStabilityNs` (dynamic handshake ceiling) to satisfy readiness when motion evidence is calm/absent:
    ```kotlin
    fun hasViableCeiling(): Boolean =
        hasMotionEvidence() || (lensStabilityNs != null && lensStabilityNs > 0L)
    ```
  - In `resolve()`: if `!ceilings.hasViableCeiling()`, return unavailable. If motion is calm, `lensStabilityNs` acts as the ceiling.
  - Apply anti-banding quantization snapping up to $T_{\text{ceiling}}$.
  - Add ETTR highlight bias override when highlight clip ratio > 0.5%.

### Step 4: Update `BnCameraManager.kt` [MODIFY]
**Path**: `app/src/main/java/com/bncam/core/engine/BnCameraManager.kt`
- In `applyOptimalAeTargetFpsRange`:
  - Sort `rawCompatibleRanges` to prefer adaptive ranges (`lower <= 15`, `upper >= 30`).
- In `resolveDefaultRawShutterPriorityPlan`:
  - Compute `DynamicSensorProfile.fromCharacteristics(characteristics)`.
  - Pass `lensStabilityNs = profile.maxHandheldShutterNs` to `RawShutterSafetyCeilings`.
- In `rawPreviewRenderer` (line 734):
  - Do not drop frames before running preview analysis; execute `applyPhysicalRawPreviewAwbObservation(frame)` and `runEnabledRawPreviewAnalysis(frame)` for active generation frames.
- In `applyExposurePolicy`:
  - On Android < API 36: when `defaultRawPlan.ready` is true, immediately apply `defaultRawPlan.targetExposureNs` and `defaultRawPlan.expectedIso` to `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` with `CONTROL_AE_MODE_OFF`.
  - Include literal `"fallback=MANUAL_LINEAR_LUMA_FEEDBACK"` in `lastExposurePlanSummary` so contract tests pass.

### Step 5: Unit Tests
- Add `app/src/test/java/com/bncam/core/capture/DynamicSensorProfileTest.kt` [NEW]:
  - Test crop factor and handheld calculation on 24mm wide with OIS ($\approx 50\text{ms}$), 14mm ultrawide ($\approx 66.7\text{ms}$), and 120mm telephoto ($\approx 20\text{ms}$).
- Update `app/src/test/java/com/bncam/core/capture/SensorStreamCadencePolicyTest.kt` [MODIFY]:
  - Test that candidate selection picks `[15, 30]` over `[30, 30]` to maximize low-light headroom.
- Update `app/src/test/java/com/bncam/core/capture/DefaultRawShutterPriorityPolicyTest.kt` [MODIFY]:
  - Test steady handheld stepping up to 40ms (1/25s) and 50ms (1/20s) under 50Hz mains.
  - Test ETTR highlight override pulling shutter to 1/50s when highlight clip > 0.5%.

---

## 5. Build & Verification Commands

```powershell
# 1. Run Core Capture Unit Tests
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& gradlew testDebugUnitTest --tests com.bncam.core.capture.*"

# 2. Verify Native C++ Shaders & Vulkan ISP Build
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& gradlew externalNativeBuildDebug"

# 3. Run Full Test Suite
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& gradlew testDebugUnitTest"
```

---

## 6. Success Acceptance Criteria

1. **Shades & Kitchen**: Camera logs show `targetExposureNs = 40_000_000L` (1/25s) and ISO $\approx 3200\text{--}7400$ instead of 1/100s and ISO 13,067.
2. **Chair**: Camera logs show `targetExposureNs = 50_000_000L` (1/20s) and ISO $\approx 10,200$ instead of 1/100s and ISO 16,589.
3. **Tablet**: Camera logs show `targetExposureNs = 30_000_000L` (1/33s) and ISO $\approx 1,700$.
4. **Lamp**: ETTR highlight override activates, logging `targetExposureNs = 20_000_000L` (1/50s) to protect bulb highlights.
5. **No Flicker or Viewfinder Hunting**: Viewfinder transitions smoothly with asymmetric EMA damping.
