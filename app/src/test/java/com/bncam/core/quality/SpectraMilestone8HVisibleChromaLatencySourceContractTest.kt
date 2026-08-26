package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HVisibleChromaLatencySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `m8h removes per-neighbour libm exp calls from production visible chroma loop`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val fastMath = File(
            app,
            "src/main/cpp/SpectraVisibleChromaFastMath.h"
        ).readText()

        assertTrue(fastMath.contains("fastVisibleGaussianWeight"))
        assertTrue(fastMath.contains("kVisibleNeighbourOffsets"))
        assertTrue(core.contains("localNoiseScale"))
        assertTrue(core.contains("maximumLumaDifference"))
        assertTrue(core.contains("maximumColourDistanceSquared"))
        assertTrue(fastMath.contains("kVisibleGaussianWeightLutIntervals"))
        assertTrue(fastMath.contains("kVisibleGaussianWeightLutMaximumAbsoluteError"))
        assertTrue(core.contains("lumaWeight"))
        assertTrue(core.contains("colourWeight"))
    }

    @Test
    fun `m8h keeps the original five by five no regret contract and exports hot loop evidence`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val telemetry = File(app, "src/main/cpp/SpectraVisibleChroma.h").readText()
        val fastMath = File(
            app,
            "src/main/cpp/SpectraVisibleChromaFastMath.h"
        ).readText()
        val trace = File(
            app,
            "src/main/java/com/bncam/core/quality/SpectraVisibleChroma.kt"
        ).readText()

        assertTrue(core.contains("resolveVisibleChromaDecision"))
        assertTrue(core.contains("supportedNeighbours"))
        assertTrue(core.contains("weightedRG"))
        assertTrue(core.contains("weightSum"))
        assertTrue(fastMath.contains("kVisibleNeighbourOffsets"))
        assertTrue(trace.contains("cpuGaussianWeightLutMaximumAbsoluteError"))
        assertTrue(trace.contains("spectraVisibleChromaCpuPerPixelScaleHoisted"))
    }
}
