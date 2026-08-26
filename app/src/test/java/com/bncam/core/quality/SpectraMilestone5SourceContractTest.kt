package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone5SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun passOneUsesGreenTensorDirectionalSupportAndSafeFallback() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val model = File(app, "src/main/cpp/SpectraAnisotropicDetail.h").readText()

        assertTrue(model.contains("StructureTensorEstimate"))
        assertTrue(model.contains("structureTensorFromMoments"))
        assertTrue(model.contains("resolveDirectionalSampleWeight"))
        assertTrue(model.contains("resolveAnisotropicAuthority"))
        assertTrue(model.contains("LOW_TENSOR_CONFIDENCE_ISOTROPIC_FALLBACK"))
        assertTrue(cpp.contains("buildSpectraStructureTensorField"))
        assertTrue(cpp.contains("interpolateSpectraStructureTensor"))
        assertTrue(cpp.contains("spectraGreenGuideAt"))
        assertTrue(cpp.contains("directional.weight"))
        assertTrue(cpp.contains("authorityDecision.finalScale"))
    }

    @Test
    fun tensorFieldIsContinuousAndDirectionalCorrectionRemainsBounded() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val model = File(app, "src/main/cpp/SpectraAnisotropicDetail.h").readText()

        assertTrue(cpp.contains("top * (1.0f - ty) + bottom * ty"))
        assertTrue(cpp.contains("edgeShiftGuard"))
        assertTrue(cpp.contains("0.018f"))
        assertTrue(model.contains("0.78f"))
        assertTrue(model.contains("crossEdgeProtected"))
        assertTrue(model.contains("alongStructureSupported"))
        assertFalse(cpp.contains("HARD_TENSOR_TILE_AUTHORITY"))
    }

    @Test
    fun schemaNineExportsTensorPercentilesOrientationAndNestedTiming() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val contract = File(app, "src/main/java/com/bncam/core/quality/SpectraAnisotropicDetail.kt").readText()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 9)
        assertTrue(trace.contains("\"anisotropicDetail\" to anisotropicDetail.toTraceMap()"))
        assertTrue(contract.contains("confidenceP10"))
        assertTrue(contract.contains("confidenceP50"))
        assertTrue(contract.contains("confidenceP90"))
        assertTrue(contract.contains("orientationHistogram"))
        assertTrue(contract.contains("NESTED_IN_SPECTRA_PASS1_PROCESSING_MS"))
        assertTrue(cpp.contains("spectraAnisotropicDetailTensorFieldBuildMs"))
        assertTrue(cpp.contains("spectraAnisotropicDetailDirectionalFilterMs"))
    }

    @Test
    fun downstreamChromaDngJniAndPublicationRemainIsolated() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val nativeLib = File(app, "src/main/cpp/native-lib.cpp").readText()
        val dngMerger = File(app, "src/main/cpp/DngMerger.cpp").readText()

        assertTrue(cpp.contains("applySpectraPass2"))
        assertTrue(cpp.contains("applySpectraPass3"))
        assertTrue(cpp.contains("applySpectraVisibleChromaPass"))
        assertFalse(nativeLib.contains("SpectraAnisotropicDetail"))
        assertFalse(dngMerger.contains("SpectraAnisotropicDetail"))
        assertFalse(cpp.contains("DNG_ANISOTROPIC_DETAIL"))
    }
}
