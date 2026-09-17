package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase13RawSensorPhysicalNoiseValidationSourceContractTest {
    @Test
    fun `raw sensor consumes the same frozen physical authority as raw10`() {
        val raw16 = File("src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()
        val master = File("src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()

        listOf(raw16, master).forEach { source ->
            val freeze = source.indexOf("withPhysicalCaptureIdentity(")
            val resolve = source.indexOf("withPhysicalNoiseAuthority()", freeze)
            val raw10 = source.indexOf("ImageFormat.RAW10 -> ImageUtils.mergeRaw10NativeRaw16Safe", resolve)
            val rawSensor = source.indexOf("ImageFormat.RAW_SENSOR -> ImageUtils.mergeRawSensorNativeRaw16Safe", resolve)
            assertTrue("capture identity must freeze before physical resolution", freeze >= 0 && resolve > freeze)
            assertTrue("RAW10 must use the already-frozen calibration", raw10 > resolve)
            assertTrue("RAW_SENSOR must use that same already-frozen calibration", rawSensor > resolve)
        }
    }

    @Test
    fun `raw sensor JNI and fusion consume only canonical physical SO`() {
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val dngMerger = File("src/main/cpp/DngMerger.cpp").readText()

        val rawSensorStart = nativeBridge.indexOf("Java_com_bncam_core_engine_ImageUtils_mergeNativeRawSensorDirectRaw16")
        assertTrue(rawSensorStart >= 0)
        val rawSensorEnd = nativeBridge.indexOf("extern \"C\"", rawSensorStart + 20)
            .let { if (it > rawSensorStart) it else nativeBridge.length }
        val rawSensorBody = nativeBridge.substring(rawSensorStart, rawSensorEnd)

        assertTrue(rawSensorBody.contains("extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray)"))
        assertTrue(rawSensorBody.contains("physicalNoiseVector(physicalNoise.s)"))
        assertTrue(rawSensorBody.contains("physicalNoiseVector(physicalNoise.o)"))
        assertFalse(rawSensorBody.contains("NoiseModelResolver"))
        assertFalse(rawSensorBody.contains("dynamicIso"))

        val wrapperStart = dngMerger.indexOf("jobject mergeRawSensorDngToRaw16(")
        assertTrue(wrapperStart >= 0)
        val wrapperEnd = dngMerger.indexOf("std::string formatDngMergeStats", wrapperStart)
        val wrapper = dngMerger.substring(wrapperStart, wrapperEnd)
        assertTrue(wrapper.contains("return mergeRawToDngRaw16Internal("))
        assertTrue(wrapper.contains("\"RAW_SENSOR\","))
        assertTrue(wrapper.contains("lockRawSensorBuffer"))
        assertTrue(wrapper.contains("unpackRawSensorToSensorRaw16"))
        assertFalse("RAW_SENSOR wrapper must not re-resolve physical S/O", wrapper.contains("NoiseModelResolver"))
    }

    @Test
    fun `raw sensor storage normalization cannot rewrite physical SO or create domain based neural strength`() {
        val rawDomain = File("src/main/java/com/bncam/core/isp/raw/RawDomainContract.kt").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val conditioning = File("src/main/cpp/SpectraNeuralConditioning.h").readText()

        val rawSensorDomainStart = rawDomain.indexOf("RawInputSource.RAW_SENSOR -> RawSampleTransform.RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD")
        assertTrue(rawSensorDomainStart >= 0)
        assertFalse("RAW_SENSOR domain mapping must not own physical noise resolution", rawDomain.contains("PhysicalNoiseModelCaptureResolver"))

        assertTrue(isp.contains("neuralEvidence.effectiveS = frozenPhysicalNoise.shotS"))
        assertTrue(isp.contains("neuralEvidence.effectiveO = frozenPhysicalNoise.readO"))

        val encodeStart = conditioning.indexOf("inline std::array<float, kGlobalConditioningValueCount> encodeGlobalConditioning")
        val encodeEnd = conditioning.indexOf("struct SpatialConditioningCell", encodeStart)
        assertTrue(encodeStart >= 0 && encodeEnd > encodeStart)
        val encodedVector = conditioning.substring(encodeStart, encodeEnd)
        assertFalse(
            "RAW10/RAW_SENSOR container label must not be an encoded Neural conditioning feature",
            encodedVector.contains("captureDomain")
        )
    }
}
