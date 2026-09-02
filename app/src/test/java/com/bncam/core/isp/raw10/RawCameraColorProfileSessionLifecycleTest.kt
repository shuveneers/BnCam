package com.bncam.core.isp.raw10

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCameraColorProfileSessionLifecycleTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `ordinary dng discovery remains next process only`() {
        val repository = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt"
        ).readText()

        val acceptBody = repository.substringAfter(
            "private fun accept(snapshot: DngCameraColorProfileSnapshot, priority: Int): Boolean {"
        ).substringBefore("private fun stageForNextProcess")
        assertTrue("if (sessionStarted.get())" in acceptBody)
        assertTrue("return stageForNextProcess(entry)" in acceptBody)
        assertFalse("installNative(entry)" in acceptBody.substringBefore("if (sessionStarted.get())"))

        val stageBody = repository.substringAfter("private fun stageForNextProcess(entry: Entry): Boolean {")
            .substringBefore("private fun validateEntry")
        assertTrue("persist(entry)" in stageBody)
        assertTrue("mergeByPriority(pendingProfiles, entry)" in stageBody)
        assertFalse("mergeByPriority(profiles, entry)" in stageBody)
        assertFalse("installNative(entry)" in stageBody)
    }

    @Test
    fun `missing oem profile may activate only before that profile first renders`() {
        val repository = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt"
        ).readText()

        val claimBody = repository.substringAfter(
            "fun shouldBootstrapBeforeFirstRender(calibrationProfileId: String): Boolean"
        ).substringBefore("fun installBootstrapDiscoveredProfile")
        assertTrue("renderedProfileIds.contains(calibrationProfileId)" in claimBody)
        assertTrue("profiles.containsKey(calibrationProfileId)" in claimBody)
        assertTrue("bootstrapAttemptedProfileIds.add(calibrationProfileId)" in claimBody)
        assertTrue("CountDownLatch(1)" in claimBody)

        val installBody = repository.substringAfter(
            "fun installBootstrapDiscoveredProfile(snapshot: DngCameraColorProfileSnapshot): Boolean {"
        ).substringBefore("fun sealForRendering")
        assertTrue("renderedProfileIds.contains(profileId)" in installBody)
        assertTrue("installNative(entry)" in installBody)
        assertTrue("mergeByPriority(profiles, entry)" in installBody)
        assertTrue("persist(entry)" in installBody)
        assertTrue("stageForNextProcess(entry)" in installBody)

        val sealBody = repository.substringAfter("fun sealForRendering(")
            .substringBefore("fun installBnCamCalibratedProfileBytes")
        assertTrue("pendingBootstrap.await(BOOTSTRAP_RENDER_WAIT_MS" in sealBody)
        assertTrue("renderedProfileIds.add(profileId)" in sealBody)
    }

    @Test
    fun `single raw builder bootstraps from direct raw16 before render seal`() {
        val builder = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt"
        ).readText()
        val bootstrap = builder.substringAfter("private fun bootstrapCameraColorProfileBeforeFirstRender(")
            .substringBefore("fun build(")

        assertTrue("shouldBootstrapBeforeFirstRender(profileId)" in bootstrap)
        assertTrue("nativeRaw16Buffer.withDirectBuffer" in bootstrap)
        assertTrue("DngWriter.discoverCameraColorProfileFromVirtualRaw16" in bootstrap)
        assertTrue("installBootstrapDiscoveredProfile(discovered)" in bootstrap)
        assertTrue("completeBootstrapAttempt(profileId)" in bootstrap)
        assertTrue("sealForRendering(" in bootstrap)
        assertFalse("materializeForDng" in bootstrap)
        assertFalse("materializeRaw16ForDng" in bootstrap)
    }

    @Test
    fun `bootstrap dng is internal bounded and never published`() {
        val writer = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw10/DngWriter.kt"
        ).readText()
        val bootstrap = writer.substringAfter("fun discoverCameraColorProfileFromVirtualRaw16(")
            .substringBefore("fun lastAuditReport")

        assertTrue("BOOTSTRAP_CAPTURE_LIMIT_BYTES" in writer)
        assertTrue("DngCreator(characteristics, metadata)" in bootstrap)
        assertTrue("writeByteBuffer(" in bootstrap)
        assertTrue("PrefixCaptureOutputStream" in bootstrap)
        assertTrue("parseCameraColorProfileBytes(" in bootstrap)
        assertFalse("MediaStore" in bootstrap)
        assertFalse("FileOutputStream" in bootstrap)
        assertFalse("ByteBuffer.wrap(raw16" in bootstrap)
    }

    @Test
    fun `persisted profiles load at manager session start and install before native warmup`() {
        val manager = File(
            appDir,
            "src/main/java/com/bncam/core/engine/BnCameraManager.kt"
        ).readText()
        assertTrue("RawCameraColorProfileRepository.beginSession(context.applicationContext)" in manager)
        val nativeWarmup = manager.substringAfter("private fun pushHardwareConfigToNative(lensId: String) {")
            .substringBefore("sessionTransitionScope.launch")
        assertTrue("RawCameraColorProfileRepository.ensureSessionProfilesInstalled()" in nativeWarmup)
        assertTrue("section = \"RAW COLOR PROFILE SESSION\"" in manager)
    }

    @Test
    fun `persistence is bounded versioned and replacement safe`() {
        val repository = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt"
        ).readText()
        assertTrue("private const val MAGIC = 0x424E4350" in repository)
        assertTrue("private const val VERSION = 1" in repository)
        assertTrue("MAX_ARRAY_FLOATS" in repository)
        assertTrue("StandardCopyOption.ATOMIC_MOVE" in repository)
        assertTrue("StandardCopyOption.REPLACE_EXISTING" in repository)
    }
}
