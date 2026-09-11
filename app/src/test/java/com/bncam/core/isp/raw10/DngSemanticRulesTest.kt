package com.bncam.core.isp.raw10

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngSemanticRulesTest {
    private fun validSnapshot(
        cfaPattern: List<Long> = listOf(2, 1, 1, 0),
        blackLevelValues: List<Double> = listOf(63.98, 64.0, 64.01, 64.01)
    ) = DngTagSnapshot(
        presentTagIds = setOf(
            256, 257, 258, 259, 262, 273, 274, 277, 278, 279,
            33421, 33422,
            50706, 50707, 50708, 50713, 50714, 50717, 50719, 50720, 50721,
            50728, 50778, 50829, 51041
        ),
        blackLevels = listOf(63, 64, 64, 64),
        whiteLevels = listOf(1023),
        orientation = 6,
        imageWidth = 4,
        imageHeight = 3,
        activeArea = listOf(0, 0, 3, 4),
        defaultCropOrigin = listOf(0, 0),
        defaultCropSize = listOf(4, 3),
        blackLevelValues = blackLevelValues,
        dngVersion = listOf(1, 4, 0, 0),
        dngBackwardVersion = listOf(1, 1, 0, 0),
        bitsPerSample = 16,
        compression = 1,
        photometricInterpretation = 32803,
        samplesPerPixel = 1,
        rowsPerStrip = 1,
        stripOffsets = listOf(16, 24, 32),
        stripByteCounts = listOf(8, 8, 8),
        cfaRepeatPatternDim = listOf(2, 2),
        cfaPattern = cfaPattern,
        blackLevelRepeatDim = listOf(2, 2),
        asShotNeutral = listOf(0.5, 1.0, 0.7),
        noiseProfile = listOf(0.001, 0.00001, 0.0011, 0.00001, 0.0012, 0.00001),
        littleEndian = true
    )

    @Test
    fun rationalBlackLevelAndCompleteCoreTagsMatchPayloadDomain() {
        val snapshot = validSnapshot()

        val items = DngSemanticRules.validateTags(
            snapshot = snapshot,
            expectedOrientation = 6,
            expectedWhiteLevel = 1023,
            expectedBlackLevels = listOf(64, 64, 64, 64),
            expectedCfaPattern = 3,
            cfaOriginX = 0,
            cfaOriginY = 0
        )

        assertTrue(items.joinToString { "${it.name}:${it.detail}" }, items.all { it.passed })
    }

    @Test
    fun wrongCfaPatternFailsAudit() {
        val snapshot = validSnapshot(cfaPattern = listOf(0, 1, 1, 2))

        val items = DngSemanticRules.validateTags(
            snapshot = snapshot,
            expectedOrientation = 6,
            expectedWhiteLevel = 1023,
            expectedBlackLevels = listOf(64, 64, 64, 64),
            expectedCfaPattern = 3
        )

        assertFalse(items.first { it.name == "CFAPattern value" }.passed)
    }

    @Test
    fun invalidNeutralAndNoiseProfileFailAudit() {
        val snapshot = validSnapshot().copy(
            asShotNeutral = listOf(0.5, 0.0, 0.7),
            noiseProfile = listOf(0.001, -0.1, 0.0011, 0.0, 0.0012, 0.0)
        )

        val items = DngSemanticRules.validateTags(
            snapshot = snapshot,
            expectedOrientation = 6,
            expectedWhiteLevel = 1023,
            expectedBlackLevels = listOf(64, 64, 64, 64),
            expectedCfaPattern = 3
        )

        assertFalse(items.first { it.name == "AsShotNeutral value" }.passed)
        assertFalse(items.first { it.name == "NoiseProfile value" }.passed)
    }

    @Test
    fun fullStripPayloadIdentityPassesForExactRaw16() {
        val source = raw16Ramp(width = 4, height = 3)
        val dng = ByteArray(40)
        source.copyInto(dng, destinationOffset = 16)
        val snapshot = validSnapshot()

        val topology = DngSemanticRules.validateUncompressedStripTopology(snapshot, 4, 3, dng.size)
        val identity = DngSemanticRules.validateDngPayloadIdentity(snapshot, source, dng, 4, 3)

        assertTrue(topology.joinToString { it.detail }, topology.all { it.passed })
        assertTrue(identity.detail, identity.passed)
    }

    @Test
    fun corruptionOutsideFirstStripIsDetected() {
        val source = raw16Ramp(width = 4, height = 3)
        val dng = ByteArray(40)
        source.copyInto(dng, destinationOffset = 16)
        dng[25] = (dng[25].toInt() xor 0x01).toByte()

        val identity = DngSemanticRules.validateDngPayloadIdentity(
            snapshot = validSnapshot(),
            sourceRaw16Bytes = source,
            dngBytes = dng,
            payloadWidth = 4,
            payloadHeight = 3
        )

        assertFalse(identity.detail, identity.passed)
        assertTrue(identity.detail.contains("strip=1"))
    }

    @Test
    fun payloadRejectsMaterialSamplesAboveDeclaredWhite() {
        val width = 20
        val height = 10
        val payload = ByteArray(width * height * 2)
        for (index in 0 until width * height) {
            val value = if (index < 4) 5000 else 1000
            payload[index * 2] = (value and 0xff).toByte()
            payload[index * 2 + 1] = (value ushr 8).toByte()
        }

        val item = DngSemanticRules.validatePayload(width, height, payload, 4095, "RAW_SENSOR")

        assertFalse(item.passed)
    }

    private fun raw16Ramp(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 2)
        for (index in 0 until width * height) {
            val value = 64 + index
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = (value ushr 8).toByte()
        }
        return out
    }
}
