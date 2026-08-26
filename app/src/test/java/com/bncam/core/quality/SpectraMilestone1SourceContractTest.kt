package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone1SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun captureRecipeHashAndJsonContainEverySpectraProfileField() {
        val app = appDir()
        val recipe = File(app, "src/main/java/com/bncam/core/capture/CaptureRecipe.kt").readText()
        val factory = File(app, "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt").readText()
        val fields = listOf(
            "spectraStrength",
            "spectraLuma",
            "spectraChroma",
            "spectraDetailProtection",
            "spectraLowFrequency"
        )

        fields.forEach { field -> assertTrue("Missing $field", recipe.contains("\"$field\"")) }
        assertTrue(recipe.contains("profileVersionMap()"))
        assertTrue(factory.contains("renderPreferences.profileVersionMap()"))
        assertTrue(factory.contains("StableJson.encode"))
        assertFalse(factory.contains("renderPreferences.profileAwb.toString()"))
        val lensSettings = File(app, "src/main/java/com/bncam/data/settings/LensHardwareSettings.kt").readText()
        assertTrue(lensSettings.contains("dynamicIsoCoeff=\$dynamicIsoCoeff"))
        assertTrue(lensSettings.contains("noiseB=\${noiseB.joinToString"))
        assertTrue(lensSettings.contains("dynamicChromaAuthorityAdjustment=\$dynamicChromaAuthorityAdjustment"))
        assertTrue(lensSettings.contains("MessageDigest.getInstance(\"SHA-256\")"))
    }

    @Test
    fun nativeRuntimeExportsPerPassTimingProvenanceAndNoRegretMetrics() {
        val app = appDir()
        val header = File(app, "src/main/cpp/IspCore.h").readText()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(header.contains("struct SpectraResidualNoiseState"))
        assertTrue(header.contains("preDemosaic"))
        assertTrue(header.contains("postDemosaic"))
        assertTrue(header.contains("postAwb"))
        assertTrue(header.contains("postColourTransform"))
        assertTrue(header.contains("postTone"))
        assertTrue(header.contains("acceptanceP10"))
        assertTrue(header.contains("meanColourShift"))
        assertTrue(header.contains("edgePreservationScore"))
        assertTrue(header.contains("oversmoothingScore"))
        assertTrue(header.contains("processingTimeMs"))
        assertTrue(cpp.contains("spectraCaptureProvenanceConfidenceP10"))
        assertTrue(cpp.contains("spectraFinalProvenanceModelMismatchP90"))
        assertTrue(cpp.contains("spectraNoRegretP2AcceptanceP50"))
        assertTrue(cpp.contains("spectraNoRegretP2InvalidTiles"))
        assertTrue(cpp.contains("spectraNoRegretP2RejectedMeanDrift"))
        assertTrue(cpp.contains("spectraPass3ProcessingTimeMs"))
        assertTrue(cpp.contains("spectraUnattributedProcessingMs"))
        assertTrue(cpp.contains("awbColourTransformMs"))
        assertTrue(cpp.contains("<< \"; awbTimingMode=fused_with_colour_transform\""))
        assertTrue(cpp.contains("<< \"; colourTransformTimingMode=fused_with_awb\""))
        assertTrue(cpp.contains("spectraResidualCovarianceRgb"))
        assertFalse(cpp.contains("residualNoiseState.covarianceRgb[0] = residualNoiseState.varianceY"))
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        assertTrue(merger.contains("spectraAcceptedFramePairs"))
        assertTrue(merger.contains("spectraAlignmentConfidence"))
        assertTrue(merger.contains("spectraMotionConfidence"))
    }

    @Test
    fun milestoneOneTraceContractsRemainWiredIntoBothCaptureRunners() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val single = File(app, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi = File(app, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 7)
        assertTrue(trace.contains("\"captureNoiseState\""))
        assertTrue(trace.contains("\"residualNoiseState\""))
        assertTrue(trace.contains("\"noRegret\""))
        assertTrue(trace.contains("\"multiFrame\""))
        assertTrue(trace.contains("\"spectraUnattributedProcessingMs\""))
        assertTrue(trace.contains("runnerPerformance?.toTraceMap()"))
        val performance = File(
            app,
            "src/main/java/com/bncam/core/debug/CapturePerformanceTracker.kt"
        ).readText()
        assertTrue(performance.contains("put(\"schemaVersion\", 2)"))
        assertTrue(performance.contains("sequentialStageDurationsMs"))
        assertTrue(performance.contains("explicitNestedDurationsMs"))
        assertTrue(performance.contains("EXPLICIT_DURATIONS_MAY_OVERLAP"))
        assertTrue(single.contains("captureAttemptId = attemptId"))
        assertTrue(multi.contains("captureAttemptId = attemptId"))
        assertTrue(single.contains("recipe = recipe"))
        assertTrue(multi.contains("recipe = recipe"))
        assertTrue(single.contains("performanceTracker.traceSnapshot()"))
        assertTrue(multi.contains("performanceTracker.traceSnapshot()"))
    }
}
