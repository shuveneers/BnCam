package com.bncam.core.isp.raw

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRaw16OwnershipContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun jpegOnlyRawPathUsesNativeDirectOwnership() {
        val app = appDir()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val singleRunner = File(app, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multiRunner = File(app, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val nativeSource = File(app, "src/main/cpp/native-lib.cpp").readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()

        assertTrue(imageUtils.contains("nativeRaw16Buffer.withDirectBuffer"))
        assertTrue(imageUtils.contains("raw16DirectBuffer = raw16DirectBuffer"))
        assertTrue(nativeSource.contains("GetDirectBufferAddress(raw16DirectBuffer)"))
        assertTrue(merger.contains("NewDirectByteBuffer(nativePayload"))
        assertTrue(singleRunner.contains("raw16ManagedHeapMaterializationCount"))
        assertTrue(multiRunner.contains("raw16ManagedHeapMaterializationCount"))
        assertFalse(singleRunner.contains("rawInput.raw16Bytes"))
        assertFalse(multiRunner.contains("masterRawFrameForDng?.raw16Bytes"))
    }

    @Test
    fun dngMaterializationIsExplicitAndNativeOwnerIsClosed() {
        val app = appDir()
        val nativeOwner = File(app, "src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt").readText()
        val singleRunner = File(app, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multiRunner = File(app, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(nativeOwner.contains("fun materializeForDng(): ByteArray"))
        assertTrue(nativeOwner.contains("AtomicBoolean"))
        assertTrue(nativeOwner.contains("managedMaterializationCount"))
        assertTrue(nativeOwner.contains("compareAndSet(0, 1)"))
        assertTrue(nativeOwner.contains("synchronized(ownershipMonitor)"))
        assertTrue(nativeOwner.contains("releaseNativeRaw16BufferSafe"))
        assertTrue(singleRunner.contains("materializeRaw16ForDng()"))
        assertTrue(multiRunner.contains("materializeRaw16ForDng()"))
        assertTrue(singleRunner.contains("singleRawFrame?.close()"))
        assertTrue(multiRunner.contains("masterRawFrameForDng?.close()"))
        assertTrue(singleRunner.contains("raw16NativeOwnerReleasedBeforePublication"))
        assertTrue(multiRunner.contains("raw16NativeOwnerReleasedBeforePublication"))
    }
}
