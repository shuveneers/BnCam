package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone6SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/DngMerger.cpp").isFile }
        ?: error("Cannot locate app module")

    private fun mergerText(app: File): String =
        File(app, "src/main/cpp/DngMerger.cpp").readText()

    @Test
    fun `temporal observer uses normalized innovation and continuous static probability`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraTemporalFusion.h").readText()

        assertTrue(merger.contains("pairVariance"))
        assertTrue(merger.contains("centredDiff / std::sqrt"))
        assertTrue(merger.contains("staticProbabilityField"))
        assertTrue(merger.contains("highConfidenceStaticSamples"))
        assertTrue(merger.contains("forwardBackwardConsistency"))
        assertTrue(merger.contains("SpectraStaticConsensus"))
        assertTrue(helper.contains("bilinearly"))
        assertTrue(helper.contains("staticProbability("))
        assertTrue(helper.contains("localFusionAuthority("))
    }

    @Test
    fun `temporal observer cannot fit or adapt physical noise model`() {
        val app = appDir()
        val helper = File(app, "src/main/cpp/SpectraTemporalFusion.h").readText()
        val merger = mergerText(app)

        assertFalse(helper.contains("fitTemporalNoiseModel("))
        assertFalse(helper.contains("fitStability("))
        assertFalse(helper.contains("WEIGHTED_LEAST_SQUARES"))
        assertFalse(helper.contains("HUBER_IRLS"))
        assertFalse(merger.contains("SpectraFitConsensus"))
        assertFalse(merger.contains("SPECTRA_ADAPTIVE_SO"))
        assertTrue(merger.contains("FROZEN_PHYSICAL_SO_READ_ONLY"))
    }

    @Test
    fun `fusion uses local motion authority and correlation aware effective frames`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val helper = File(app, "src/main/cpp/SpectraTemporalFusion.h").readText()

        assertTrue(merger.contains("correlationWeighted32"))
        assertTrue(merger.contains("localStaticProbability"))
        assertTrue(merger.contains("supportWeight <= 1.0e-5"))
        assertTrue(merger.contains("spectraFusionVarianceP10"))
        assertTrue(merger.contains("spectraEffectiveFrameCountP90"))
        assertTrue(helper.contains("correlationAwareVarianceScale"))
        assertFalse(merger.contains("rho*(1-q)"))
    }

    @Test
    fun `trace schema 10 exposes milestone 6 without new JNI signatures`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        val mergerHeader = File(app, "src/main/cpp/DngMerger.h").readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(schemaVersion >= 10)
        assertTrue(trace.contains("SpectraTemporalFusionTrace.fromNativeStats"))
        assertTrue(trace.contains("\"temporalFusion\" to temporalFusion.toTraceMap()"))
        assertTrue(mergerHeader.contains("spectraPersistentPatternFraction"))
        assertTrue(mergerHeader.contains("spectraForwardBackwardConsistency"))
        assertTrue(nativeBridge.contains("mergeRaw10DngToRaw16"))
    }
}
