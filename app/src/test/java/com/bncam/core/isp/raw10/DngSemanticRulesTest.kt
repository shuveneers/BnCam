package com.bncam.core.isp.raw10

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngSemanticRulesTest {
    @Test
    fun completeTagSnapshotMatchesPayloadDomain() {
        val snapshot = DngTagSnapshot(
            presentTagIds = setOf(
                50714, 50717, 33421, 33422, 50829, 50719, 50720, 50728,
                50721, 51041, 50778, 274
            ),
            blackLevels = listOf(64, 65, 66, 67),
            whiteLevels = listOf(4095),
            orientation = 6,
            imageWidth = 4000,
            imageHeight = 3000,
            activeArea = listOf(0, 0, 3000, 4000),
            defaultCropOrigin = listOf(0, 0),
            defaultCropSize = listOf(4000, 3000)
        )

        val items = DngSemanticRules.validateTags(snapshot, 6, 4095, listOf(64, 65, 66, 67))

        assertTrue(items.joinToString { it.detail }, items.all { it.passed })
    }

    @Test
    fun wrongBlackLevelAndMissingNoiseProfileFailAudit() {
        val snapshot = DngTagSnapshot(
            presentTagIds = setOf(50714, 50717, 33421, 33422, 50829, 50719, 50720, 50728, 50721, 50778, 274),
            blackLevels = listOf(0, 0, 0, 0),
            whiteLevels = listOf(4095),
            orientation = 1,
            imageWidth = 4000,
            imageHeight = 3000,
            activeArea = listOf(0, 0, 3000, 4000),
            defaultCropOrigin = listOf(0, 0),
            defaultCropSize = listOf(4000, 3000)
        )

        val items = DngSemanticRules.validateTags(snapshot, 1, 4095, listOf(64, 64, 64, 64))

        assertFalse(items.first { it.name == "NoiseProfile" }.passed)
        assertFalse(items.first { it.name == "BlackLevel value" }.passed)
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
}
