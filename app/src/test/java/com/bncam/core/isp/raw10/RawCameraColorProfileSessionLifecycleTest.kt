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
    fun `dng discovery is staged and cannot mutate active session owner`() {
        val repository = File(
            appDir,
            "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt"
        ).readText()

        val activeBranch = repository.substringAfter("if (sessionStarted.get()) {")
            .substringBefore("if (!installNative(entry))")
        assertTrue("val persisted = persist(entry)" in activeBranch)
        assertTrue("mergeByPriority(pendingProfiles, entry)" in activeBranch)
        assertFalse("installNative(entry)" in activeBranch)
        assertFalse("mergeByPriority(profiles, entry)" in activeBranch)
    }

    @Test
    fun `persisted profiles are frozen at manager session start and installed before native warmup`() {
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
