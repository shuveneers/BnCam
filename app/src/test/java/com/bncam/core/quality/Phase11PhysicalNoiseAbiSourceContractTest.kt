package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11PhysicalNoiseAbiSourceContractTest {
    @Test
    fun `jni exposes exactly one canonical physical noise carrier`() {
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val nativeConfig = File("src/main/cpp/NativeRenderQualityConfig.h").readText()

        assertTrue(imageUtils.contains("physicalNoiseSo: DoubleArray"))
        assertTrue(nativeBridge.contains("jdoubleArray physicalNoiseSoArray"))
        assertTrue(nativeBridge.contains("extractCanonicalPhysicalNoiseSo(env, physicalNoiseSoArray)"))
        assertTrue(nativeConfig.contains("bool physicalNoiseJniPayloadReceived = false"))
        assertTrue(nativeConfig.contains("double effectiveS[4]"))
        assertTrue(nativeConfig.contains("double effectiveO[4]"))

        val forbiddenNativeCarriers = listOf(
            "effectiveNoiseProfile[",
            "hasNoiseProfile",
            "noiseProfileApplied",
            "noiseProfileValid",
            "noiseProfilePairCount",
            "noiseProfileChannelCount",
            "spectraCameraS",
            "spectraCameraO",
            "spectraEffectiveS",
            "spectraEffectiveO",
            "spectraSnapshotPresent"
        )
        forbiddenNativeCarriers.forEach { token ->
            assertFalse("legacy/parallel native noise carrier must be removed: $token", nativeConfig.contains(token))
            assertFalse("legacy/parallel JNI noise carrier must be removed: $token", nativeBridge.contains(token))
        }
    }

    @Test
    fun `canonical payload order is R Gr Gb B interleaved S O`() {
        val physicalState = File("src/main/java/com/bncam/core/quality/PhysicalNoiseState.kt").readText()
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()

        assertTrue(imageUtils.contains("PhysicalNoiseSoContract.pack(effectiveS, effectiveO)"))
        val kotlinOrder = listOf(
            "effectiveS[0], effectiveO[0]",
            "effectiveS[1], effectiveO[1]",
            "effectiveS[2], effectiveO[2]",
            "effectiveS[3], effectiveO[3]"
        )
        var cursor = -1
        kotlinOrder.forEach { token ->
            val next = physicalState.indexOf(token, cursor + 1)
            assertTrue("canonical Kotlin physical S/O order must contain $token", next > cursor)
            cursor = next
        }

        assertTrue(nativeBridge.contains("raw[ch * 2]"))
        assertTrue(nativeBridge.contains("raw[ch * 2 + 1]"))
        assertTrue(nativeBridge.contains("out.s[static_cast<std::size_t>(ch)] = sValue"))
        assertTrue(nativeBridge.contains("out.o[static_cast<std::size_t>(ch)] = oValue"))
    }
}
