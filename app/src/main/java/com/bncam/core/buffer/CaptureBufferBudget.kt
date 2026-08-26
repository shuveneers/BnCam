package com.bncam.core.buffer

import android.graphics.ImageFormat
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameCapacityResolution
import kotlin.math.min

data class CaptureBufferBudgetResult(
    val capacity: Int,
    val estimatedFrameBytes: Long,
    val targetBudgetBytes: Long,
    val estimatedCapacityBytes: Long,
    val minimumCapacityFrames: Int,
    val routeMaximumFrames: Int,
    val runtimeSafeCapacity: Int,
    val capacityResolution: FrameCapacityResolution
)

object CaptureBufferBudget {
    private const val MIB = 1024L * 1024L

    fun resolve(
        format: Int,
        width: Int,
        height: Int,
        totalRamBytes: Long,
        memoryClassBytes: Long,
        captureMode: CaptureMode = CaptureMode.SINGLE
    ): CaptureBufferBudgetResult {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height.toLong()
        val frameBytes = when (format) {
            ImageFormat.RAW_SENSOR -> pixels * 2L
            ImageFormat.RAW10 -> (pixels * 5L + 3L) / 4L
            ImageFormat.YUV_420_888 -> (pixels * 3L + 1L) / 2L
            else -> pixels * 2L
        }

        val origin = FrameCapacityPolicy.frameOrigin(format)
        val routeMaximum = FrameCapacityPolicy.warmBufferTarget(origin)

        val ramBudget = (totalRamBytes.coerceAtLeast(1L) * 5L) / 100L
        val processBudget = memoryClassBytes.coerceAtLeast(1L)
        val targetBudget = min(ramBudget, processBudget)
            .coerceAtMost(512L * MIB)
            .coerceAtLeast(frameBytes)

        val runtimeSafeCapacity = (targetBudget / frameBytes.coerceAtLeast(1L))
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val resolution = FrameCapacityPolicy.resolveWarmBuffer(
            origin = origin,
            runtimeSafeMaximum = runtimeSafeCapacity,
            captureMode = captureMode
        )
        val capacity = resolution.effectiveValue
        return CaptureBufferBudgetResult(
            capacity = capacity,
            estimatedFrameBytes = frameBytes,
            targetBudgetBytes = targetBudget,
            estimatedCapacityBytes = frameBytes * capacity,
            minimumCapacityFrames = 1,
            routeMaximumFrames = routeMaximum,
            runtimeSafeCapacity = runtimeSafeCapacity,
            capacityResolution = resolution
        )
    }
}
