package com.bncam.core.isp.raw10

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5DngCorrectnessSourceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence()
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Source not found: $path")
    }

    @Test
    fun semanticAuditUsesRationalBlackAndFullStripPayloadIdentity() {
        val audit = source("src/main/java/com/bncam/core/isp/raw10/DngSemanticAudit.kt")

        assertTrue(audit.contains("tags[50714]?.valuesAsDoubles"))
        assertTrue(audit.contains("validateUncompressedStripTopology"))
        assertTrue(audit.contains("validateDngPayloadIdentity"))
        assertTrue(audit.contains("Stage-E (DNG Payload Exact-Identity Proven)"))
        assertFalse(audit.contains("val stripOffset = tags?.get(273)?.valuesAsLongs(1)"))
        assertFalse(audit.contains("val stripByteCount = tags?.get(279)?.valuesAsLongs(1)"))
    }

    @Test
    fun failedSemanticAuditBlocksPublicationAndProfileInstallation() {
        val writer = source("src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")

        val auditIndex = writer.indexOf("val auditReport = DngSemanticAuditor.audit(")
        val gateIndex = writer.indexOf("if (!auditReport.passed)", auditIndex)
        val blockIndex = writer.indexOf("DNG_CORRECTNESS_BLOCKED", gateIndex)
        val installIndex = writer.indexOf("RawCameraColorProfileRepository.installDiscoveredProfile(", gateIndex)

        assertTrue(auditIndex >= 0)
        assertTrue(gateIndex > auditIndex)
        assertTrue(blockIndex > gateIndex)
        assertTrue(installIndex > blockIndex)
        assertTrue(writer.substring(gateIndex, installIndex).contains("return null"))
    }

    @Test
    fun coreDngContractIsExplicitlyValidated() {
        val rules = source("src/main/java/com/bncam/core/isp/raw10/DngSemanticRules.kt")

        listOf(
            "\"BitsPerSample\"",
            "\"Compression\"",
            "\"PhotometricInterpretation\"",
            "\"SamplesPerPixel\"",
            "\"CFAPattern value\"",
            "\"BlackLevel value\"",
            "\"WhiteLevel value\"",
            "\"AsShotNeutral value\"",
            "\"NoiseProfile value\"",
            "\"Strip topology\"",
            "\"DNG RAW payload identity\""
        ).forEach { required ->
            assertTrue("Missing DNG correctness contract: $required", rules.contains(required))
        }
    }
}
