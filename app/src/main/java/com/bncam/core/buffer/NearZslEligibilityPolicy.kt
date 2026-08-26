package com.bncam.core.buffer

import android.hardware.camera2.CaptureResult

object NearZslEligibilityPolicy {
    /**
     * Conservative 30 ms rolling shutter readout estimate used when SENSOR_ROLLING_SHUTTER_SKEW
     * is omitted or null by the HAL. This prevents misclassifying a rolling-shutter exposure
     * as ending up to ~30 ms earlier than physical reality.
     */
    const val CONSERVATIVE_FALLBACK_ROLLING_SHUTTER_SKEW_NS = 30_000_000L // 30 ms

    fun calculateFullExposureEndNs(pair: ZslFramePair): Long {
        val sensorStartNs = try { pair.metadata?.get(CaptureResult.SENSOR_TIMESTAMP) } catch (_: Throwable) { null } ?: pair.timestamp
        val exposureTimeNs = try { pair.metadata?.get(CaptureResult.SENSOR_EXPOSURE_TIME) } catch (_: Throwable) { null } ?: pair.exposureTimeNs
        val rollingShutterSkewNs = try { pair.metadata?.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) } catch (_: Throwable) { null }
            ?: if (pair.rollingShutterSkewNs > 0L) pair.rollingShutterSkewNs else CONSERVATIVE_FALLBACK_ROLLING_SHUTTER_SKEW_NS
        return sensorStartNs + exposureTimeNs + rollingShutterSkewNs
    }

    fun isFullyPreShutter(
        pair: ZslFramePair,
        userShutterTimestampNs: Long,
        shutterTimestampDomain: String = "ELAPSED_REALTIME"
    ): Boolean {
        if (userShutterTimestampNs <= 0L) return true

        if (shutterTimestampDomain == "SENSOR_TIMESTAMP") {
            return calculateFullExposureEndNs(pair) <= userShutterTimestampNs
        }

        if (shutterTimestampDomain.startsWith("ELAPSED_REALTIME")) {
            if (pair.sensorTimestampComparableToElapsedRealtime) {
                return calculateFullExposureEndNs(pair) <= userShutterTimestampNs
            }

            // When SENSOR_TIMESTAMP has an unrelated/UNKNOWN clock origin, comparing the two
            // numeric timestamps is invalid. A pair that was already fully assembled before the
            // user shutter is a conservative, clock-safe ZSL candidate: both image and metadata
            // had arrived before the shutter event, so its exposure necessarily predates it.
            val completionElapsedNs = pair.pairCompleteElapsedNs.takeIf { it > 0L }
                ?: maxOf(pair.imageArrivalElapsedNs, pair.metadataArrivalElapsedNs)
            return completionElapsedNs > 0L && completionElapsedNs <= userShutterTimestampNs
        }

        // Unknown shutter domains are deliberately not cross-compared with sensor timestamps.
        return false
    }
}
