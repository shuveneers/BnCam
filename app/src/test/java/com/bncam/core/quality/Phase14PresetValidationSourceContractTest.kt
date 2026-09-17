package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase14PresetValidationSourceContractTest {
    @Test
    fun `preset ids never receive runtime special casing`() {
        val resolver = File("src/main/java/com/bncam/data/settings/PhysicalNoiseModelCaptureAuthority.kt").readText()
        val physical = File("src/main/java/com/bncam/core/quality/PhysicalNoiseModel.kt").readText()

        listOf(resolver, physical).forEach { source ->
            assertFalse(source.contains("agc_v12:80"))
            assertFalse(source.contains("NubiaZ50Ultra"))
            assertFalse(source.contains("OV64B"))
        }
        assertTrue(resolver.contains("source = NoiseModelSource.PRESET"))
        assertTrue(resolver.contains("model = it.model"))
        assertTrue(physical.contains("val profile = request.model.resolveAt(effectiveIso)"))
    }

    @Test
    fun `preset dynamic iso is upstream and evaluated once`() {
        val physical = File("src/main/java/com/bncam/core/quality/PhysicalNoiseModel.kt").readText()
        val resolveParametric = physical.substring(
            physical.indexOf("private fun resolveParametric(request: Request.Parametric)"),
            physical.indexOf("private fun validateCaptureIdentity", physical.indexOf("private fun resolveParametric(request: Request.Parametric)"))
        )

        assertTrue(resolveParametric.contains("val effectiveIso = resolveParametricIso("))
        assertTrue(resolveParametric.contains("val profile = request.model.resolveAt(effectiveIso)"))
        assertFalse(resolveParametric.contains("effectiveNoiseModelIso("))
    }
}
