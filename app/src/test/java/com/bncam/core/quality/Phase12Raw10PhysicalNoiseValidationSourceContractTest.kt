package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase12Raw10PhysicalNoiseValidationSourceContractTest {
    @Test
    fun `raw10 and raw sensor share one physical noise authority before container specific unpack`() {
        val raw16 = File("src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()
        val master = File("src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        listOf(raw16, master).forEach { source ->
            val freeze = source.indexOf("withPhysicalCaptureIdentity(")
            val resolve = source.indexOf("withPhysicalNoiseAuthority()", freeze)
            val raw10 = source.indexOf("ImageFormat.RAW10 -> ImageUtils.mergeRaw10NativeRaw16Safe", resolve)
            val rawSensor = source.indexOf("ImageFormat.RAW_SENSOR -> ImageUtils.mergeRawSensorNativeRaw16Safe", resolve)
            assertTrue("capture identity must freeze before physical resolution", freeze >= 0 && resolve > freeze)
            assertTrue("RAW10 must consume the already-resolved shutter calibration", raw10 > resolve)
            assertTrue("RAW_SENSOR must consume the same already-resolved shutter calibration", rawSensor > resolve)
        }

        assertTrue(imageUtils.contains("val physicalTemporalNoise = finalCalibration.toPhysicalTemporalNoisePayload()"))
        assertTrue(imageUtils.contains("physicalNoiseSo = physicalTemporalNoise.toInterleavedSoOrEmpty()"))
        assertTrue(imageUtils.contains("PhysicalNoiseSoContract.pack(effectiveS, effectiveO)"))
        assertFalse("RAW10 must not own a second noise resolver", raw16.contains("PhysicalNoiseModelCaptureResolver"))
        assertFalse("RAW10 must not own a second noise resolver", master.contains("PhysicalNoiseModelCaptureResolver"))
    }

    @Test
    fun `raw10 and raw sensor JNI decode the exact same canonical payload`() {
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val dngMerger = File("src/main/cpp/DngMerger.cpp").readText()

        val raw10Start = nativeBridge.indexOf("Java_com_bncam_core_engine_ImageUtils_mergeNativeRaw10DirectRaw16")
        val rawSensorStart = nativeBridge.indexOf("Java_com_bncam_core_engine_ImageUtils_mergeNativeRawSensorDirectRaw16")
        assertTrue(raw10Start >= 0 && rawSensorStart > raw10Start)

        val raw10Body = nativeBridge.substring(raw10Start, rawSensorStart)
        val rawSensorBody = nativeBridge.substring(rawSensorStart, nativeBridge.indexOf("extern \"C\"", rawSensorStart + 20).let { if (it > rawSensorStart) it else nativeBridge.length })
        listOf(raw10Body, rawSensorBody).forEach { body ->
            assertTrue(body.contains("extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray)"))
            assertTrue(body.contains("physicalNoiseVector(physicalNoise.s)"))
            assertTrue(body.contains("physicalNoiseVector(physicalNoise.o)"))
        }

        assertTrue(dngMerger.contains("return mergeRawToDngRaw16Internal("))
        assertTrue(dngMerger.contains("\"RAW10\",\n            lockRaw10Buffer,\n            unpackRaw10ToSensorRaw16"))
        assertTrue(dngMerger.contains("\"RAW_SENSOR\",\n            lockRawSensorBuffer,\n            unpackRawSensorToSensorRaw16"))
    }

    @Test
    fun `raw10 only differs in physical storage domain not neural physical SO`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val conditioningStart = isp.indexOf("neuralEvidence.framePhysics.captureDomain")
        val conditioningEnd = isp.indexOf("neuralEvidence.userControls", conditioningStart)
        assertTrue(conditioningStart >= 0 && conditioningEnd > conditioningStart)
        val conditioning = isp.substring(conditioningStart, conditioningEnd)

        assertTrue(conditioning.contains("RawCaptureDomain::Raw10"))
        assertTrue(conditioning.contains("RawCaptureDomain::RawSensor"))
        assertFalse("RAW10 must not rescale physical S/O inside Neural conditioning", conditioning.contains("effectiveS[") || conditioning.contains("effectiveO["))

        assertTrue(isp.contains("neuralEvidence.effectiveS = frozenPhysicalNoise.shotS"))
        assertTrue(isp.contains("neuralEvidence.effectiveO = frozenPhysicalNoise.readO"))
    }
}
