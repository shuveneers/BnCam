package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase6BSpectraAddonSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun bothRawBuildersAttachSpectraOnlyAfterPhysicalAuthority() {
        val paths = listOf(
            "app/src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt",
            "app/src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt"
        )
        paths.forEach { path ->
            val text = source(path)
            val physical = text.indexOf("withPhysicalNoiseAuthority()")
            val spectra = text.indexOf("withSpectraNoiseAdapter()")
            assertTrue(physical >= 0)
            assertTrue(spectra > physical)
            assertTrue(text.contains("withPhysicalMergeStats"))
            assertFalse(text.contains("withSpectraMergeStats"))
        }
    }

    @Test
    fun spectraAdapterCannotWritePhysicalModelFields() {
        val text = source("app/src/main/java/com/bncam/core/quality/SpectraNoiseAdapter.kt")
        assertTrue(text.contains("physicalSOImmutable=true"))
        assertFalse(text.contains("effectiveS = input.effectiveS"))
        assertFalse(text.contains("effectiveO = input.effectiveO"))
        assertFalse(text.contains("PhysicalNoiseModelRuntimeRegistry"))
    }

    @Test
    fun legacyPolicyNoLongerUsesPhysicalAvailabilityToEraseSpectraRequest() {
        val text = source("app/src/main/java/com/bncam/core/quality/NoiseModelAuthorityPolicy.kt")
        assertTrue(text.contains("val spectraMode = if (spectraRequested) ON else OFF"))
        assertFalse(text.contains("if (spectraRequested && physicalModelAvailable)"))
    }
}
