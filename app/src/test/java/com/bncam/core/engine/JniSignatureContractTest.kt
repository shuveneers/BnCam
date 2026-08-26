package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JniSignatureContractTest {
    @Test
    fun rawPreviewExternalFunctionsAreMembersOfImageUtilsObject() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/ImageUtils.kt").isFile }
            ?: error("Cannot locate app module from ${File(".").absolutePath}")
        val kotlinSource = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val objectStart = kotlinSource.indexOf("object ImageUtils")
        val bodyStart = kotlinSource.indexOf('{', objectStart)
        var depth = 0
        var bodyEnd = -1
        for (index in bodyStart until kotlinSource.length) {
            when (kotlinSource[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        bodyEnd = index
                        break
                    }
                }
            }
        }

        assertTrue("ImageUtils object body was not found", objectStart >= 0 && bodyStart >= 0 && bodyEnd > bodyStart)
        listOf(
            "retainRawPreviewHardwareBufferNative",
            "releaseRawPreviewHardwareBufferNative",
            "bindRawPreviewHardwareBufferToCurrentTextureNative",
            "releaseRawPreviewEglImageNative",
            "createRawPreviewGlFenceNative",
            "pollRawPreviewGlFenceNative",
            "destroyRawPreviewGlFenceNative",
            "getRawPreviewEglNextFrameIdNative",
            "getRawPreviewEglDisplayPresentTimeNative",
            "getPreviewBufferTelemetryNative",
            "renderRawPreviewNative"
        ).forEach { functionName ->
            val declaration = kotlinSource.indexOf("external fun $functionName")
            assertTrue(
                "$functionName must stay inside ImageUtils so JNI resolves Java_com_bncam_core_engine_ImageUtils_$functionName",
                declaration in (bodyStart + 1) until bodyEnd
            )
        }
    }

    @Test
    fun imageUtilsExternalFunctionsHaveExactNativeSymbols() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/ImageUtils.kt").isFile }
            ?: error("Cannot locate app module from ${File(".").absolutePath}")
        val kotlinSource = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeSource = File(appDir, "src/main/cpp/native-lib.cpp").readText()

        val kotlinExternalNames = Regex("external\\s+fun\\s+(\\w+)")
            .findAll(kotlinSource)
            .map { it.groupValues[1] }
            .toSet()
        val nativeSymbolNames = Regex("Java_com_bncam_core_engine_ImageUtils_(\\w+)")
            .findAll(nativeSource)
            .map { it.groupValues[1] }
            .toSet()
        val expected = setOf(
            "analyzeFrameCandidateNative",
            "bindRawPreviewHardwareBufferToCurrentTextureNative",
            "createRawPreviewGlFenceNative",
            "destroyRawPreviewGlFenceNative",
            "getLastDngMergeStatsNative",
            "getLastMasterIspStatsNative",
            "getLastYuvStatsNative",
            "getNativeRaw16OutstandingBufferCountNative",
            "getNativeStageHeartbeatJsonNative",
            "getPreviewBufferTelemetryNative",
            "getRawPreviewEglDisplayPresentTimeNative",
            "getRawPreviewEglNextFrameIdNative",
            "mergeNativeRaw10DirectRaw16",
            "mergeNativeRawSensorDirectRaw16",
            "pollRawPreviewGlFenceNative",
            "processNativeYuv",
            "releaseNativeRaw16Buffer",
            "releaseRawPreviewEglImageNative",
            "releaseRawPreviewHardwareBufferNative",
            "renderJpegFromMasterNative",
            "renderRawPreviewNative",
            "retainRawPreviewHardwareBufferNative",
            "updateHardwareConfigNative",
            "validateDemosaicNative",
            "validateNoiseModelNative",
            "validateOisStabilizedNative"
        )

        assertEquals(expected, kotlinExternalNames)
        assertEquals(expected, nativeSymbolNames)
        assertTrue(nativeSource.contains("GetDirectBufferAddress(raw16DirectBuffer)"))
        assertTrue(nativeSource.contains("GetDirectBufferCapacity(raw16DirectBuffer)"))
        assertTrue(!nativeSource.contains("GetPrimitiveArrayCritical(raw16BayerBytes"))
    }
}
