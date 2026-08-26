package com.bncam.core.performance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level release gates for the approved Phase 1A lifecycle and Phase 1B RAW owner. */
class Phase1NativeOwnershipContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun singleYuvTransfersSelectedFrameLeaseToProcessingQueue() {
        val source =
            File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val yuvSection = source.substringAfter(
            "// Single YUV previously rendered synchronously"
        )

        assertTrue(source.contains("anchorLease = ringBuffer.leaseFrame(anchorFrame)"))
        assertTrue(yuvSection.contains("val yuvAnchorLease = requireNotNull(anchorLease)"))
        assertTrue(yuvSection.contains("CaptureProcessingQueue.tryReserve"))
        assertTrue(yuvSection.contains("CaptureProcessingQueue.submit"))
        assertTrue(yuvSection.contains("yuvAnchorLease.release()"))
        assertTrue(yuvSection.contains("anchorLease = null"))
        assertTrue(
            yuvSection.contains(
                "SELECTED_FRAME_LEASE_TRANSFERRED_TO_PROCESSING_QUEUE"
            )
        )
        assertTrue(yuvSection.contains("CaptureSubmissionResult.Submitted"))
        assertFalse(yuvSection.contains("candidateFramesOwnershipTransferred"))
        assertFalse(yuvSection.contains("CompletedSynchronously"))
    }

    @Test
    fun rawContractsOwnOneNativeDirectBufferInsteadOfJavaPayloads() {
        val contract =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()
        val master =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()
        val owner =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt").readText()

        assertTrue(contract.contains("val nativeRaw16Buffer: NativeRaw16Buffer"))
        assertFalse(contract.contains("val raw16Bytes: ByteArray"))
        assertTrue(master.contains("override val nativeRaw16Buffer: NativeRaw16Buffer"))
        assertTrue(owner.contains("private val directBuffer: ByteBuffer"))
        assertTrue(owner.contains("require(directBuffer.isDirect)"))
        assertTrue(owner.contains("withDirectBuffer"))
    }

    @Test
    fun jpegOnlyRawDoesNotRequireManagedRaw16Materialization() {
        val owner =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt").readText()
        val single =
            File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi =
            File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(owner.contains("fun materializeForDng(): ByteArray"))
        assertTrue(owner.contains("compareAndSet(0, 1)"))
        assertTrue(single.contains("expectedMaterializations = if (plan.outputPolicy.producesRaw) 1 else 0"))
        assertTrue(multi.contains("JPEG-only multi-frame capture materialized RAW16"))
        assertTrue(multi.contains("raw16ManagedMaterializationCountSnapshot == 0"))
    }

    @Test
    fun nativeDirectAllocationHasOneTrackedReleaseBoundary() {
        val dngMerger = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val owner =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/NativeRaw16Buffer.kt").readText()

        assertTrue(dngMerger.contains("std::unordered_set<void*> gNativeRaw16Allocations"))
        assertTrue(dngMerger.contains("trackNativeRaw16Allocation"))
        assertTrue(dngMerger.contains("releaseNativeRaw16Allocation"))
        assertTrue(nativeBridge.contains("GetDirectBufferAddress"))
        assertTrue(nativeBridge.contains("releaseNativeRaw16Allocation(address)"))
        assertTrue(owner.contains("compareAndSet(false, true)"))
        assertTrue(owner.contains("releaseNativeRaw16BufferSafe(directBuffer)"))
    }

    @Test
    fun nativeRendererConsumesDirectBufferWithoutJavaArrayLock() {
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val renderer = native.substringAfter("raw16DirectBuffer")
            .substringBefore("Java_com_bncam_core_engine_ImageUtils_validateOisStabilizedNative")

        assertTrue(renderer.contains("GetDirectBufferAddress"))
        assertTrue(renderer.contains("GetDirectBufferCapacity"))
        assertFalse(renderer.contains("GetPrimitiveArrayCritical"))
        assertTrue(native.contains("raw16ManagedHeapCopyCount=0"))
    }

    @Test
    fun captureUiUsesLifecycleOwnedScopeAndHandlesOrdinaryFailure() {
        val screen =
            File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        val trigger = screen.substringAfter("val triggerCaptureSequence = {")
            .substringBefore("// EVENT BUS LISTENER")

        assertTrue(screen.contains("val captureScope = remember(lifecycleOwner) { lifecycleOwner.lifecycleScope }"))
        assertTrue(trigger.contains("captureScope.launch"))
        assertTrue(trigger.contains("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        assertTrue(trigger.contains("catch (failure: Throwable)"))
        assertFalse(trigger.contains("CountDownLatch"))
    }

    @Test
    fun rawBuildersReleaseOwnerWhenContractResolutionFails() {
        val single =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()
        val master =
            File(appDir, "src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()

        assertTrue(single.contains("nativeRaw16Buffer.close()"))
        assertTrue(single.contains("catch (failure: Throwable)"))
        assertTrue(master.contains("nativeRaw16Buffer.close()"))
        assertTrue(master.contains("catch (failure: Throwable)"))
    }

    @Test
    fun ownershipDiagnosticsAndTerminalInvariantsRemainPresent() {
        val imageUtils =
            File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val single =
            File(appDir, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val multi =
            File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()

        assertTrue(imageUtils.contains("getNativeRaw16OutstandingBufferCountNative"))
        assertTrue(single.contains("raw16NativeOutstandingBuffersAtPublication"))
        assertTrue(multi.contains("raw16NativeOutstandingBuffersAtPublication"))
        assertTrue(single.contains("nativeRaw16Buffer.isClosed"))
        assertTrue(multi.contains("raw16NativeOwnerReleasedBeforePublication"))
    }

    @Test
    fun multiFramePublicationIsLifecycleOwnedAndSubmitted() {
        val multi =
            File(appDir, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val submitIndex = multi.indexOf("CaptureProcessingQueue.submit")
        val publishIndex = multi.indexOf("workReservation.markPublished")
        val returnIndex = multi.lastIndexOf("CaptureSubmissionResult.Submitted")

        assertTrue(submitIndex >= 0)
        assertTrue(publishIndex > submitIndex)
        assertTrue(returnIndex > submitIndex)
        assertTrue(multi.contains("masterRawFrameForDng?.close()"))
        assertTrue(multi.contains("closeBurstFrames()"))
    }
}
