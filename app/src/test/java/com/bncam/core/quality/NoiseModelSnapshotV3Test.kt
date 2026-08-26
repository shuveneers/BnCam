package com.bncam.core.quality

import com.bncam.data.settings.StableLensKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseModelSnapshotV3Test {

    @Test
    fun testNeutralCreationAndDefaults() {
        val snapshot = NoiseModelSnapshotV3.createNeutral(
            lensKey = "0",
            sourceFormat = "RAW10",
            iso = 200,
            whiteLevel = 1023
        )

        assertNotNull(snapshot)
        assertEquals(200, snapshot.iso)
        assertEquals(1023, snapshot.whiteLevel)
        assertEquals("RAW10", snapshot.sourceFormat)
        assertEquals("Legacy", snapshot.spectraMode)
        assertFalse(snapshot.isSpectraActive())
        assertTrue(snapshot.stableLensKey.value.startsWith("lens_v2_"))
    }

    @Test
    fun testStableLensKeyResolutionConsistency() {
        val keyRaw = "0"
        val expectedStableKey = StableLensKey.fromRawPhysicalId(keyRaw).value

        val snapshot = NoiseModelSnapshotV3.createNeutral(lensKey = keyRaw)
        assertEquals(expectedStableKey, snapshot.stableLensKey.value)

        val raw10Snapshot = NoiseModelSnapshotV3.createNeutral(lensKey = keyRaw, sourceFormat = "RAW10")
        val rawSensorSnapshot = NoiseModelSnapshotV3.createNeutral(lensKey = keyRaw, sourceFormat = "RAW_SENSOR")

        assertEquals(raw10Snapshot.stableLensKey, rawSensorSnapshot.stableLensKey)
    }

    @Test
    fun testSpectraActiveStates() {
        val legacy = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse(legacy.isSpectraActive())

        val off = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Off")
        assertFalse(off.isSpectraActive())

        val auto = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Auto")
        assertTrue(auto.isSpectraActive())

        val manual = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Manual")
        assertTrue(manual.isSpectraActive())
    }

    @Test
    fun testStrictFourStepImmutabilityProof() {
        // Step 1: Construct snapshot from source arrays
        val sourceBlack = floatArrayOf(64f, 64f, 64f, 64f)
        val sourceEffS = doubleArrayOf(0.001, 0.001, 0.001, 0.001)
        val snapshot = NoiseModelSnapshotV3.createNeutral(
            blackLevel = sourceBlack
        ).copy(effectiveS = sourceEffS)

        // Step 2: Mutate original source arrays
        sourceBlack[0] = 9999f
        sourceEffS[0] = 9.999

        // Step 3: Retrieve array from getter and mutate returned array
        val retrievedBlack = snapshot.blackLevel
        val retrievedEffS = snapshot.effectiveS
        retrievedBlack[0] = 8888f
        retrievedEffS[0] = 8.888

        // Step 4: Verify snapshot internal values remain strictly unchanged
        assertEquals(64f, snapshot.blackLevel[0], 0.001f)
        assertEquals(0.001, snapshot.effectiveS[0], 0.000001)
    }

    @Test
    fun testAllBayerArrangementsSupport() {
        val cfaPatterns = listOf(
            0 to "RGGB",
            1 to "GRBG",
            2 to "GBRG",
            3 to "BGGR"
        )

        for ((code, name) in cfaPatterns) {
            val snapshot = NoiseModelSnapshotV3.createNeutral(
                cfaPattern = code,
                cfaName = name
            )
            assertEquals(code, snapshot.cfaPattern)
            assertEquals(name, snapshot.cfaName)
        }
    }

    @Test
    fun testLegacyModeIsolationAndInactivity() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        val offSnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Off")

        assertFalse(legacySnapshot.isSpectraActive())
        assertFalse(offSnapshot.isSpectraActive())
        assertEquals("Legacy", legacySnapshot.spectraMode)
        assertEquals("Off", offSnapshot.spectraMode)
    }

    @Test
    fun testControlledSnapshotParameterDivergence() {
        val neutralSnapshot = NoiseModelSnapshotV3.createNeutral().copy(
            effectiveS = doubleArrayOf(0.001, 0.001, 0.001, 0.001),
            effectiveO = doubleArrayOf(0.0001, 0.0001, 0.0001, 0.0001)
        )
        val modifiedSnapshot = neutralSnapshot.copy(
            effectiveS = doubleArrayOf(0.005, 0.005, 0.005, 0.005),
            effectiveO = doubleArrayOf(0.0008, 0.0008, 0.0008, 0.0008)
        )

        assertNotEquals(neutralSnapshot, modifiedSnapshot)
        assertFalse(neutralSnapshot.effectiveS.contentEquals(modifiedSnapshot.effectiveS))
        assertFalse(neutralSnapshot.effectiveO.contentEquals(modifiedSnapshot.effectiveO))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidWhiteLevelThrows() {
        NoiseModelSnapshotV3.createNeutral(whiteLevel = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBlankLensKeyThrows() {
        NoiseModelSnapshotV3(
            lensKey = "   ",
            sourceFormat = "RAW10",
            iso = 100,
            exposureTimeNs = 10_000_000L,
            postRawSensitivityBoost = null,
            cfaPattern = 0,
            cfaName = "RGGB",
            whiteLevel = 1023,
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            cameraS = DoubleArray(4),
            cameraO = DoubleArray(4),
            effectiveS = DoubleArray(4),
            effectiveO = DoubleArray(4),
            chromaUserScale = 1.0f,
            lumaUserScale = 1.0f
        )
    }
}
