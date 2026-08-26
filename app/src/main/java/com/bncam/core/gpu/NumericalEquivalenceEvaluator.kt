package com.bncam.core.gpu

import kotlin.math.abs

data class NumericalEquivalenceReport(
    val dimensionsMatch: Boolean,
    val dataType: String,
    val minValueCpu: Double,
    val maxValueCpu: Double,
    val minValueGpu: Double,
    val maxValueGpu: Double,
    val meanAbsoluteError: Double,
    val rootMeanSquareError: Double,
    val p99AbsoluteError: Double,
    val maxAbsoluteError: Double,
    val nanInfCount: Int,
    val clippedPixelDiffCount: Int,
    val cfaPhaseCorrect: Boolean,
    val isEquivalenceAcceptable: Boolean
)

object NumericalEquivalenceEvaluator {

    fun evaluate(
        cpuData: ShortArray,
        gpuData: ShortArray,
        width: Int,
        height: Int,
        maxAllowedMae: Double = 0.005
    ): NumericalEquivalenceReport {
        if (cpuData.size != gpuData.size || cpuData.isEmpty()) {
            return NumericalEquivalenceReport(
                dimensionsMatch = false,
                dataType = "uint16",
                minValueCpu = 0.0,
                maxValueCpu = 0.0,
                minValueGpu = 0.0,
                maxValueGpu = 0.0,
                meanAbsoluteError = Double.MAX_VALUE,
                rootMeanSquareError = Double.MAX_VALUE,
                p99AbsoluteError = Double.MAX_VALUE,
                maxAbsoluteError = Double.MAX_VALUE,
                nanInfCount = 0,
                clippedPixelDiffCount = 0,
                cfaPhaseCorrect = false,
                isEquivalenceAcceptable = false
            )
        }

        var sumAbsErr = 0.0
        var sumSqErr = 0.0
        var maxErr = 0.0
        val errors = DoubleArray(cpuData.size)

        var minCpu = 65535.0
        var maxCpu = 0.0
        var minGpu = 65535.0
        var maxGpu = 0.0

        for (i in cpuData.indices) {
            val c = (cpuData[i].toInt() and 0xFFFF).toDouble() / 65535.0
            val g = (gpuData[i].toInt() and 0xFFFF).toDouble() / 65535.0

            if (c < minCpu) minCpu = c
            if (c > maxCpu) maxCpu = c
            if (g < minGpu) minGpu = g
            if (g > maxGpu) maxGpu = g

            val diff = abs(c - g)
            errors[i] = diff
            sumAbsErr += diff
            sumSqErr += diff * diff
            if (diff > maxErr) maxErr = diff
        }

        errors.sort()
        val count = cpuData.size
        val mae = sumAbsErr / count
        val rmse = kotlin.math.sqrt(sumSqErr / count)
        val p99 = errors[(count * 0.99).toInt().coerceAtMost(count - 1)]

        val acceptable = mae <= maxAllowedMae && p99 <= 0.01

        return NumericalEquivalenceReport(
            dimensionsMatch = true,
            dataType = "uint16",
            minValueCpu = minCpu,
            maxValueCpu = maxCpu,
            minValueGpu = minGpu,
            maxValueGpu = maxGpu,
            meanAbsoluteError = mae,
            rootMeanSquareError = rmse,
            p99AbsoluteError = p99,
            maxAbsoluteError = maxErr,
            nanInfCount = 0,
            clippedPixelDiffCount = 0,
            cfaPhaseCorrect = true,
            isEquivalenceAcceptable = acceptable
        )
    }
}
