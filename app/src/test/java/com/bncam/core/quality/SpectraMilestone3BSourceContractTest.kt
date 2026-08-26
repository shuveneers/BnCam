package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone3BSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun activeFineMidLowKernelsAreBandLimitedAndIndependentlyPlanned() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val planner = File(app, "src/main/cpp/SpectraChromaBands.h").readText()
        val kernels = File(app, "src/main/cpp/SpectraChromaMultiscale.h").readText()

        assertTrue(planner.contains("M3B_FINE_WIENER_DETAIL_RADIUS2"))
        assertTrue(planner.contains("M3B_MID_ATROUS_RESIDUAL_RADIUS2_TO6"))
        assertTrue(planner.contains("M3B_LOW_CONTINUOUS_FIELD_SOFT_THRESHOLD"))
        assertTrue(kernels.contains("resolveBandKernelDecision"))
        assertTrue(kernels.contains("resolveLowBandFieldDecision"))
        assertTrue(kernels.contains("resolveMidBandCoefficient"))
        assertTrue(kernels.contains("signalVariance"))
        assertTrue(cpp.contains("GREEN_GUIDED_FINE_WIENER_SHRINKAGE_RADIUS2"))
        assertTrue(cpp.contains("GREEN_GUIDED_ATROUS_MID_SHRINKAGE_LOCAL2_TO_BROAD4_6"))
        assertTrue(cpp.contains("CONTINUOUS_LOW_FIELD_SOFT_THRESHOLD_PLUS_DIRECTIONAL_PROFILES"))
        assertTrue(cpp.contains("pass2State.midBand.plan.enabled"))
        assertTrue(cpp.contains("pass2State.blendStrength"))
        assertFalse(cpp.contains("ENABLED_AS_MID_BAND_PREREQUISITE_M3A"))
        assertFalse(cpp.contains("POST_DEMOSAIC_VISIBLE_CHROMA_CORRECTION_M3B"))
    }

    @Test
    fun continuousStructureAndModelControlsBoundEveryBand() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val kernels = File(app, "src/main/cpp/SpectraChromaMultiscale.h").readText()

        assertTrue(kernels.contains("finiteUnit(planAuthority) * finiteUnit(modelConfidence)"))
        assertTrue(kernels.contains("decision.structureWeight"))
        assertTrue(kernels.contains("decision.shadowWeight"))
        assertTrue(kernels.contains("bandCeiling"))
        assertTrue(kernels.contains("0.92f"))
        assertTrue(kernels.contains("0.78f"))
        assertTrue(kernels.contains("0.68f"))
        assertTrue(cpp.contains("std::exp(-2.35f * gradientNorm)"))
        assertTrue(cpp.contains("std::exp(-1.75f * structureNorm)"))
        assertTrue(cpp.contains("std::exp(-1.65f * gradientNorm)"))
        assertTrue(cpp.contains("applySpectraNoRegretGate"))
    }

    @Test
    fun schemaSevenExportsKernelLevelObservabilityWithoutDoubleCountingTiming() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val contract = File(app, "src/main/java/com/bncam/core/quality/SpectraChromaBands.kt").readText()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 7)
        assertTrue(contract.contains("SPECTRA_CONTEXT_FUSION_ACTIVE_MULTISCALE_CHROMA"))
        assertTrue(contract.contains("runtimeMethod"))
        assertTrue(contract.contains("coefficientEnergyBefore"))
        assertTrue(contract.contains("candidatePixelCount"))
        assertTrue(contract.contains("meanShrinkage"))
        assertTrue(contract.contains("shrinkageP90"))
        assertTrue(contract.contains("NESTED_IN_PASS2_PASS3_TOTALS"))
        assertTrue(cpp.contains("CoefficientEnergyBefore"))
        assertTrue(cpp.contains("StructureRejectedPixelCount"))
        assertTrue(cpp.contains("ShrinkageP90"))
    }

    @Test
    fun offDngJniAndPostDemosaicContractsRemainUntouched() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(cpp.contains("SPECTRA_OFF_NOT_MEASURED"))
        assertTrue(cpp.contains("if (budgetState.spectraMode != 0)"))
        assertFalse(cpp.contains("POST_DEMOSAIC_VISIBLE_CHROMA_CORRECTION_M3B"))
        assertTrue(cpp.contains("SPECTRA_CONTEXT_FUSION_PROPAGATED_VISIBLE_CHROMA"))
        assertFalse(File(app, "src/main/cpp/native-lib.cpp").readText().contains("Milestone3B"))
    }
}
