package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone7SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `downstream sharpening consumes residual state and keeps spectra off legacy route`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraDownstreamIsp.h").readText()

        assertTrue(helper.contains("resolveDownstreamSharpenPlan"))
        assertTrue(helper.contains("resolveDownstreamPixelDecision"))
        assertTrue(helper.contains("maximumAmountForVarianceGain"))
        assertTrue(core.contains("LEGACY_EDGE_AWARE_SHARPEN_APPLIED"))
        assertTrue(core.contains("if (spectraNoiseActive)"))
        assertTrue(core.contains("budgetState.downstreamLumaAuthority"))
        assertTrue(core.contains("uiConfig.profileSpectraDetailProtection"))
        assertTrue(helper.contains("PROPAGATED_PLUS_BOUNDED_PRE_SHARPEN_MEASUREMENT"))
    }

    @Test
    fun `final residual state includes quantization and noise aware sharpening`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraDownstreamIsp.h").readText()
        val residual = File(
            app,
            "src/main/java/com/bncam/core/quality/ResidualNoiseState.kt"
        ).readText()

        assertTrue(helper.contains("propagateQuantization8Bit"))
        assertTrue(helper.contains("propagateNoiseAwareSharpen"))
        assertTrue(core.contains("residualNoiseState.postQuantization"))
        assertTrue(core.contains("residualNoiseState.finalJpeg"))
        assertTrue(core.contains("FINAL_JPEG_PRE_ENCODE_AFTER_SHARPEN"))
        assertTrue(residual.contains("\"noiseAwareSharpen\" to transitionTraceMap"))
        assertTrue(residual.contains("\"postQuantization\" to state.postQuantization.toTraceMap()"))
        assertTrue(residual.contains("\"finalJpeg\" to state.finalJpeg.toTraceMap()"))
    }

    @Test
    fun `schema 11 exports complete downstream observability`() {
        val app = appDir()
        val trace = File(
            app,
            "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt"
        ).readText()
        val downstream = File(
            app,
            "src/main/java/com/bncam/core/quality/SpectraDownstreamIsp.kt"
        ).readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(schemaVersion >= 11)
        assertTrue(trace.contains("SpectraDownstreamIspTrace.fromNativeStats"))
        assertTrue(trace.contains("\"downstreamIsp\" to downstreamIsp.toTraceMap()"))
        assertTrue(trace.contains("spectraDownstreamPropagationMs"))
        assertTrue(downstream.contains("noiseRejectedPixelCount"))
        assertTrue(downstream.contains("maximumPredictedVarianceGain"))
        assertTrue(downstream.contains("localContrastApplied"))
    }

    @Test
    fun `milestone 7 does not add downstream processing to dng or jni`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertFalse(merger.contains("SpectraDownstreamIsp"))
        assertFalse(merger.contains("noiseAwareSharpen"))
        assertFalse(nativeBridge.contains("SpectraDownstreamIsp"))
    }
}
