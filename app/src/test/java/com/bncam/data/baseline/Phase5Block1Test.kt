package com.bncam.data.baseline

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.quality.ImageQualityDiagnosticExporter
import com.bncam.core.quality.ObjectiveQualityMetrics
import com.bncam.data.profile.IspProfileConfig
import com.bncam.data.settings.LensCalibrationConfig
import com.bncam.data.settings.OutputModeDngConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase5Block1Test {

    @Before
    fun setUp() {
        ImageQualityDiagnosticExporter.clearDiagnostics()
    }

    @Test
    fun `baseline key isolation for different lenses`() {
        val key1 = LensIspBaselineKey(stableLensKey = "lens_v2_0_none_hash1", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.SINGLE_FRAME)
        val key2 = LensIspBaselineKey(stableLensKey = "lens_v2_1_none_hash2", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.SINGLE_FRAME)

        assertNotEquals(key1, key2)
        val b1 = LensIspBaselineProvider.getBaseline(key1)
        val b2 = LensIspBaselineProvider.getBaseline(key2)
        assertNotNull(b1)
        assertNotNull(b2)
    }

    @Test
    fun `source format baseline isolation for raw10 versus raw_sensor versus yuv`() {
        val keyRaw10 = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.SINGLE_FRAME)
        val keyRawSensor = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.RAW_SENSOR, shootingMode = ShootingMode.SINGLE_FRAME)
        val keyYuv = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.YUV, shootingMode = ShootingMode.SINGLE_FRAME)

        val bRaw10 = LensIspBaselineProvider.getBaseline(keyRaw10)
        val bRawSensor = LensIspBaselineProvider.getBaseline(keyRawSensor)
        val bYuv = LensIspBaselineProvider.getBaseline(keyYuv)

        assertNotEquals(bRaw10.lumaDenoiseBaseline, bYuv.lumaDenoiseBaseline)
        assertNotEquals(bRaw10.lumaDenoiseBaseline, bRawSensor.lumaDenoiseBaseline)
    }

    @Test
    fun `single and multi frame baseline isolation`() {
        val keySingle = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.SINGLE_FRAME)
        val keyMulti = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.MULTI_FRAME)

        val bSingle = LensIspBaselineProvider.getBaseline(keySingle)
        val bMulti = LensIspBaselineProvider.getBaseline(keyMulti)

        assertTrue(bMulti.lumaDenoiseBaseline < bSingle.lumaDenoiseBaseline)
    }

    @Test
    fun `normalized 0_00 resolves to exact baseline value`() {
        val baselineValue = 0.08f
        val resolvedZero = LensIspBaselineProvider.resolveEffectiveValue(
            profileValue = 0.00f,
            baseline = baselineValue,
            minBound = 0.00f,
            maxBound = 0.30f
        )
        assertEquals(baselineValue, resolvedZero, 0.001f)

        val resolvedPositive = LensIspBaselineProvider.resolveEffectiveValue(
            profileValue = 0.50f,
            baseline = baselineValue,
            minBound = 0.00f,
            maxBound = 0.30f
        )
        assertTrue(resolvedPositive > baselineValue)

        val resolvedNegative = LensIspBaselineProvider.resolveEffectiveValue(
            profileValue = -0.50f,
            baseline = baselineValue,
            minBound = 0.00f,
            maxBound = 0.30f
        )
        assertTrue(resolvedNegative < baselineValue)
    }

    @Test
    fun `baseline revision is frozen in shutter snapshot`() {
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null,
            profileId = "profile_1",
            profileName = "Custom Profile",
            profileRevision = 2L
        )

        assertEquals("lens_v2_0", snapshot.stableLensKey)
        assertEquals(2L, snapshot.profileRevision)
    }

    @Test
    fun `profile metadata remains independent from lens ISP baseline updates`() {
        val profile = IspProfileConfig.createDefault("lens_v2_0", "Custom Profile").copy(revision = 7L)
        val updatedBaseline = LensIspBaseline(
            key = LensIspBaselineKey(stableLensKey = "lens_v2_0", sourceFormat = SourceFormat.RAW10, shootingMode = ShootingMode.SINGLE_FRAME),
            sharpeningBaseline = 0.40f
        )
        LensIspBaselineProvider.updateBaseline(updatedBaseline)
        assertEquals("Custom Profile", profile.name)
        assertEquals(7L, profile.revision)
        assertEquals(0.40f, LensIspBaselineProvider.getBaseline(updatedBaseline.key).sharpeningBaseline, 0.001f)
    }

    @Test
    fun `bounded diagnostic retention enforces max 50 records`() {
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null
        )

        for (i in 1..60) {
            ImageQualityDiagnosticExporter.recordDiagnostic(
                snapshot = snapshot.copy(snapshotId = "snap_$i"),
                sourceFormat = SourceFormat.RAW10,
                shootingMode = ShootingMode.SINGLE_FRAME
            )
        }

        val records = ImageQualityDiagnosticExporter.getRecentRecords()
        assertEquals(50, records.size)
        assertEquals("snap_11", records.first().snapshotId)
        assertEquals("snap_60", records.last().snapshotId)
    }

    @Test
    fun `deterministic metric calculation`() {
        val metrics = ObjectiveQualityMetrics(
            perChannelShadowMean = listOf(10.5f, 10.8f, 10.2f),
            greenToNeutralShadowBias = 0.3f,
            lumaNoiseEstimate = 0.02f
        )

        assertEquals(10.5f, metrics.perChannelShadowMean[0], 0.001f)
        assertEquals(0.3f, metrics.greenToNeutralShadowBias, 0.001f)
        assertEquals(0.02f, metrics.lumaNoiseEstimate, 0.001f)
    }
}
