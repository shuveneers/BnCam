package com.bncam.core.isp.raw10

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCameraColorProfileSessionLifecycleTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt").isFile }
        ?: error("Unable to locate app module")

    private fun source(path: String): String = File(appDir, path).readText()

    @Test
    fun `persisted profiles stay candidates until exact current binding is registered`() {
        val repository = source("src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt")
        val begin = repository.substringAfter("fun beginSession(context: Context) {")
            .substringBefore("fun registerExpectedCalibrationBinding")
        assertTrue("persistedCandidates" in begin)
        assertFalse("mergeByPriority(profiles" in begin)
        assertFalse("installNative(" in begin)

        val bind = repository.substringAfter("fun registerExpectedCalibrationBinding")
            .substringBefore("fun ensureSessionProfilesInstalled")
        assertTrue("staticCalibrationFingerprint" in bind)
        assertTrue("snapshotMatchesBinding" in bind)
        assertTrue("persistedCandidates[key]" in bind)
        assertTrue("installNative(active)" in bind)
    }

    @Test
    fun `missing exact profile may bootstrap only before first render`() {
        val repository = source("src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt")
        val claim = repository.substringAfter("fun shouldBootstrapBeforeFirstRender(calibrationBinding: CalibrationProfileBinding)")
            .substringBefore("fun completeBootstrapAttempt")
        assertTrue("registerExpectedCalibrationBinding(calibrationBinding)" in claim)
        assertTrue("calibrationBinding.safeForProfileBinding" in claim)
        assertTrue("renderedProfileIds.contains(calibrationProfileId)" in claim)
        assertTrue("profiles.containsKey(calibrationProfileId)" in claim)

        val install = repository.substringAfter("fun installBootstrapDiscoveredProfile")
            .substringBefore("fun sealForRendering")
        assertTrue("snapshotMatchesBinding" in install)
        assertTrue("installNative(entry)" in install)
        assertTrue("persist(entry)" in install)
    }

    @Test
    fun `single raw builder passes immutable calibration binding through bootstrap and seal`() {
        val builder = source("src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt")
        val bootstrap = builder.substringAfter("private fun bootstrapCameraColorProfileBeforeFirstRender(")
            .substringBefore("fun build(")
        assertTrue("calibration?.base?.calibrationProfileBinding" in bootstrap)
        assertTrue("shouldBootstrapBeforeFirstRender(calibrationBinding)" in bootstrap)
        assertTrue("calibrationBinding = calibrationBinding!!" in bootstrap)
        assertTrue("installBootstrapDiscoveredProfile(discovered)" in bootstrap)
        assertTrue("sealForRendering(" in bootstrap)
    }

    @Test
    fun `bootstrap dng is private and profile parser receives exact binding`() {
        val writer = source("src/main/java/com/bncam/core/isp/raw10/DngWriter.kt")
        val bootstrap = writer.substringAfter("fun discoverCameraColorProfileFromVirtualRaw16(")
            .substringBefore("fun lastAuditReport")
        assertTrue("ByteArrayOutputStream" in writer)
        assertTrue("DngCreator(characteristics, metadata)" in bootstrap)
        assertTrue("calibrationBinding: CalibrationProfileBinding" in bootstrap)
        assertTrue("calibrationBinding = calibrationBinding" in bootstrap)
        assertTrue("parseCameraColorProfileBytes(" in bootstrap)
        assertFalse("MediaStore" in bootstrap)
    }

    @Test
    fun `persistence v2 is authority and calibration fingerprint keyed`() {
        val repository = source("src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt")
        assertTrue("private const val MAGIC = 0x424E4350" in repository)
        assertTrue("private const val VERSION = 2" in repository)
        assertTrue("raw_camera_color_profiles_v2" in repository)
        assertTrue("entry.snapshot.sensorAuthorityId" in repository)
        assertTrue("entry.snapshot.staticCalibrationFingerprint" in repository)
        assertTrue("StandardCopyOption.ATOMIC_MOVE" in repository)
        assertTrue("StandardCopyOption.REPLACE_EXISTING" in repository)
    }
}
