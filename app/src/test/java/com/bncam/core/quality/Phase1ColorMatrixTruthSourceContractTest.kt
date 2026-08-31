package com.bncam.core.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Phase1ColorMatrixTruthSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `metadata matrix is preserved without neutral row normalization`() {
        val source = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        assertTrue("preNormalizationValues = values.copyOf()" in source)
        assertTrue("neutralNormalizationApplied = false" in source)
        assertTrue("baseColorMatrixPreNormalization = colorMatrix.preNormalizationValues?.copyOf()" in source)
        assertTrue("legacyNeutralNormalizedValues = legacyCounterfactual" in source)
    }

    @Test
    fun `diagnostics expose pre and effective matrix row sums separately`() {
        val source = File(appDir, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        assertTrue("Color Matrix Metadata Pre-Normalization Values" in source)
        assertTrue("Color Matrix Pre-Normalization Row Sums" in source)
        assertTrue("Color Matrix Effective Row Sums" in source)
        assertTrue("Color Matrix Neutral Row Normalization Applied" in source)
    }
}
