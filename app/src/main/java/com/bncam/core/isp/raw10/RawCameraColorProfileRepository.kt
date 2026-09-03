package com.bncam.core.isp.raw10

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trusted camera colour-characterization repository with a pre-render session freeze.
 *
 * A physical DNG camera profile remains useful even when ProfileHueSatMap is absent. Matrix,
 * CameraCalibration and ForwardMatrix characterization are therefore validated independently from
 * the optional HueSatMap payload. A present but malformed HueSatMap still fails closed.
 */
object RawCameraColorProfileRepository {
    private const val TAG = "RawCameraColorProfileRepo"
    private const val PRIORITY_OEM = 300
    private const val PRIORITY_BNCAM = 200
    private const val PRIORITY_EXTERNAL = 100
    private const val MAGIC = 0x424E4350 // BNCP
    private const val VERSION = 1
    private const val MAX_ARRAY_FLOATS = (1 shl 20) * 3
    private const val STORE_DIR = "raw_camera_color_profiles_v1"
    private const val BOOTSTRAP_RENDER_WAIT_MS = 1_500L

    private data class Entry(val snapshot: DngCameraColorProfileSnapshot, val priority: Int)

    private val profiles = ConcurrentHashMap<String, Entry>()
    private val pendingProfiles = ConcurrentHashMap<String, Entry>()
    private val nativeInstalled = ConcurrentHashMap.newKeySet<String>()
    private val sessionStarted = AtomicBoolean(false)
    private val sessionNativeReady = AtomicBoolean(false)
    private val renderedProfileIds = ConcurrentHashMap.newKeySet<String>()
    private val bootstrapAttemptedProfileIds = ConcurrentHashMap.newKeySet<String>()
    private val bootstrapCompletionSignals = ConcurrentHashMap<String, CountDownLatch>()
    private val lock = Any()

    @Volatile private var storageDirectory: File? = null
    @Volatile private var sessionLoadCount: Int = 0
    @Volatile private var lastPersistenceError: String = "none"
    @Volatile private var bootstrapInstallCount: Int = 0
    @Volatile private var bootstrapStatus: String = "NOT_ATTEMPTED"
    @Volatile private var lastRenderSealSource: String = "NOT_SEALED"

    fun beginSession(context: Context) {
        if (!sessionStarted.compareAndSet(false, true)) return
        synchronized(lock) {
            val dir = File(context.filesDir, STORE_DIR)
            storageDirectory = dir
            if (!dir.exists() && !dir.mkdirs()) {
                lastPersistenceError = "mkdir_failed:${dir.absolutePath}"
                Log.w(TAG, "profile store unavailable: $lastPersistenceError")
                return@synchronized
            }
            val loaded = loadPersistedEntries(dir)
            loaded.forEach { entry -> mergeByPriority(profiles, entry) }
            sessionLoadCount = profiles.size
            Log.i(
                TAG,
                "session loaded: persistedCandidates=${profiles.size}; missing OEM profile may bootstrap before first RAW render"
            )
        }
        ensureSessionProfilesInstalled()
    }

    fun ensureSessionProfilesInstalled(): Boolean {
        if (!sessionStarted.get()) return false
        return synchronized(lock) {
            var allInstalled = true
            profiles.entries
                .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.priority }.thenBy { it.key })
                .forEach { (id, entry) ->
                    if (!nativeInstalled.contains(id)) {
                        val installed = installNative(entry)
                        if (installed) nativeInstalled.add(id) else allInstalled = false
                    }
                }
            sessionNativeReady.set(allInstalled)
            allInstalled
        }
    }

    fun installDiscoveredProfile(snapshot: DngCameraColorProfileSnapshot): Boolean =
        accept(snapshot.copy(source = "OEM_DNGCREATOR"), PRIORITY_OEM)

    fun shouldBootstrapBeforeFirstRender(calibrationProfileId: String): Boolean = synchronized(lock) {
        if (!sessionStarted.get() || renderedProfileIds.contains(calibrationProfileId) ||
            calibrationProfileId.isBlank() || calibrationProfileId == "unknown" ||
            profiles.containsKey(calibrationProfileId)
        ) {
            return@synchronized false
        }
        val claimed = bootstrapAttemptedProfileIds.add(calibrationProfileId)
        if (claimed) {
            bootstrapCompletionSignals.putIfAbsent(calibrationProfileId, CountDownLatch(1))
            bootstrapStatus = "CLAIMED:$calibrationProfileId"
            Log.i(TAG, "pre-render OEM colour bootstrap claimed: id=$calibrationProfileId")
        }
        claimed
    }

    fun completeBootstrapAttempt(calibrationProfileId: String) {
        val profileId = calibrationProfileId.ifBlank { "unknown" }
        bootstrapCompletionSignals[profileId]?.countDown()
        synchronized(lock) {
            if (bootstrapStatus == "CLAIMED:$profileId") {
                bootstrapStatus = "COMPLETE_NO_PROFILE:$profileId"
            }
        }
    }

    fun installBootstrapDiscoveredProfile(snapshot: DngCameraColorProfileSnapshot): Boolean {
        val entry = validateEntry(
            snapshot.copy(source = "OEM_DNGCREATOR_BOOTSTRAP"),
            PRIORITY_OEM
        ) ?: return synchronized(lock) {
            bootstrapStatus = "REJECTED_INVALID:${snapshot.calibrationProfileId}"
            false
        }
        return synchronized(lock) {
            val profileId = entry.snapshot.calibrationProfileId
            if (!sessionStarted.get() || renderedProfileIds.contains(profileId) ||
                !bootstrapAttemptedProfileIds.contains(profileId)
            ) {
                bootstrapStatus = "REJECTED_AFTER_RENDER_SEAL:$profileId"
                Log.w(TAG, "pre-render OEM colour bootstrap rejected after render seal: id=$profileId")
                return@synchronized stageForNextProcess(entry)
            }
            val existing = profiles[profileId]
            if (existing != null && existing.priority > entry.priority) {
                bootstrapStatus = "REJECTED_LOWER_PRIORITY:$profileId"
                return@synchronized false
            }
            if (!installNative(entry)) {
                bootstrapStatus = "NATIVE_INSTALL_FAILED:$profileId"
                stageForNextProcess(entry)
                return@synchronized false
            }
            mergeByPriority(profiles, entry)
            nativeInstalled.add(profileId)
            sessionNativeReady.set(profiles.keys.all(nativeInstalled::contains))
            val persisted = persist(entry)
            if (!persisted) {
                Log.w(TAG, "bootstrap profile active but persistence failed: id=$profileId")
            }
            bootstrapInstallCount++
            bootstrapStatus = "INSTALLED:$profileId"
            Log.i(
                TAG,
                "pre-render OEM colour profile installed: id=$profileId source=${entry.snapshot.source} " +
                    "status=${entry.snapshot.status} hsm=${entry.snapshot.hueSatMap.available}/${entry.snapshot.hueSatMap.valid}"
            )
            true
        }
    }

    fun sealForRendering(
        calibrationProfileId: String,
        source: String = "RAW_RENDER"
    ): Boolean {
        val profileId = calibrationProfileId.ifBlank { "unknown" }
        val pendingBootstrap = bootstrapCompletionSignals[profileId]
        if (pendingBootstrap != null && pendingBootstrap.count > 0L) {
            val completed = runCatching {
                pendingBootstrap.await(BOOTSTRAP_RENDER_WAIT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!completed) {
                synchronized(lock) { bootstrapStatus = "WAIT_TIMEOUT:$profileId" }
                Log.w(
                    TAG,
                    "pre-render colour bootstrap wait timed out: id=$profileId waitMs=$BOOTSTRAP_RENDER_WAIT_MS; sealing current owner"
                )
            }
        }
        val firstSeal = renderedProfileIds.add(profileId)
        if (firstSeal) {
            lastRenderSealSource = source.ifBlank { "RAW_RENDER" }
            Log.i(
                TAG,
                "camera colour owner sealed for rendering: id=$profileId source=$lastRenderSealSource " +
                    "activeProfiles=${profiles.size} bootstrapStatus=$bootstrapStatus"
            )
        }
        ensureSessionProfilesInstalled()
        return firstSeal
    }

    fun installBnCamCalibratedProfileBytes(
        bytes: ByteArray,
        calibrationProfileId: String,
        discoveryEffectiveCcm: FloatArray
    ): Boolean = accept(
        DngSemanticAuditor.parseCameraColorProfileBytes(
            bytes, calibrationProfileId, discoveryEffectiveCcm, "BNCAM_CALIBRATED_PROFILE"
        ),
        PRIORITY_BNCAM
    )

    fun installExternalDngOrDcpProfileBytes(
        bytes: ByteArray,
        calibrationProfileId: String,
        discoveryEffectiveCcm: FloatArray
    ): Boolean = accept(
        DngSemanticAuditor.parseCameraColorProfileBytes(
            bytes, calibrationProfileId, discoveryEffectiveCcm, "EXTERNAL_DNG_DCP_PROFILE"
        ),
        PRIORITY_EXTERNAL
    )

    fun snapshot(calibrationProfileId: String): DngCameraColorProfileSnapshot? =
        profiles[calibrationProfileId]?.snapshot?.copied()

    fun debugSummary(): String =
        "sessionStarted=${sessionStarted.get()}; renderedProfileOwners=${renderedProfileIds.size}; " +
            "lastRenderSealSource=$lastRenderSealSource; persistedLoaded=$sessionLoadCount; " +
            "activeProfiles=${profiles.size}; nativeInstalled=${nativeInstalled.size}; " +
            "nativeReady=${sessionNativeReady.get()}; preRenderBootstrapAttempts=${bootstrapAttemptedProfileIds.size}; " +
            "preRenderBootstrapInstalled=$bootstrapInstallCount; bootstrapStatus=$bootstrapStatus; " +
            "pendingNextProcess=${pendingProfiles.size}; persistenceError=$lastPersistenceError"

    private fun accept(snapshot: DngCameraColorProfileSnapshot, priority: Int): Boolean {
        val entry = validateEntry(snapshot, priority) ?: return false
        if (sessionStarted.get()) {
            return stageForNextProcess(entry)
        }

        if (!installNative(entry)) return false
        mergeByPriority(profiles, entry)
        nativeInstalled.add(entry.snapshot.calibrationProfileId)
        Log.i(
            TAG,
            "profile installed outside frozen session: id=${entry.snapshot.calibrationProfileId} " +
                "source=${entry.snapshot.source} status=${entry.snapshot.status}"
        )
        return true
    }

    private fun stageForNextProcess(entry: Entry): Boolean {
        val existingActive = profiles[entry.snapshot.calibrationProfileId]
        if (existingActive != null && existingActive.priority > entry.priority) return false
        val existingPending = pendingProfiles[entry.snapshot.calibrationProfileId]
        if (existingPending != null && existingPending.priority > entry.priority) return false
        val persisted = persist(entry)
        if (!persisted) return false
        mergeByPriority(pendingProfiles, entry)
        Log.i(
            TAG,
            "profile staged for next process: id=${entry.snapshot.calibrationProfileId} " +
                "source=${entry.snapshot.source} status=${entry.snapshot.status}; active session unchanged"
        )
        return true
    }

    private fun validateEntry(snapshot: DngCameraColorProfileSnapshot, priority: Int): Entry? {
        if (!snapshot.available || !snapshot.valid || snapshot.calibrationProfileId.isBlank() ||
            snapshot.calibrationProfileId == "unknown"
        ) {
            Log.i(
                TAG,
                "profile rejected: id=${snapshot.calibrationProfileId} source=${snapshot.source} status=${snapshot.status}"
            )
            return null
        }

        val cm1 = snapshot.colorMatrix1 ?: return null
        val fm1 = snapshot.forwardMatrix1 ?: return null
        val discovery = snapshot.discoveryEffectiveCcm ?: return null
        if (cm1.size != 9 || fm1.size != 9 || discovery.size != 9 || snapshot.analogBalance.size != 3) return null
        if (cm1.any { !it.isFinite() } || fm1.any { !it.isFinite() } || discovery.any { !it.isFinite() }) return null
        if (snapshot.analogBalance.any { !it.isFinite() || it <= 0f || it > 64f }) return null
        if (snapshot.calibrationIlluminant1 == 0) return null

        val hasSecondCharacterization = snapshot.colorMatrix2 != null ||
            snapshot.cameraCalibration2 != null || snapshot.forwardMatrix2 != null ||
            snapshot.calibrationIlluminant2 != 0
        if (hasSecondCharacterization &&
            (snapshot.colorMatrix2?.size != 9 || snapshot.forwardMatrix2?.size != 9 ||
                snapshot.calibrationIlluminant2 == 0)
        ) return null

        listOf(
            snapshot.colorMatrix2,
            snapshot.cameraCalibration1,
            snapshot.cameraCalibration2,
            snapshot.forwardMatrix1,
            snapshot.forwardMatrix2
        ).forEach { matrix ->
            if (matrix != null && (matrix.size != 9 || matrix.any { !it.isFinite() })) return null
        }

        val hsm = snapshot.hueSatMap
        if (!hsm.available) {
            if (hsm.valid || hsm.data1 != null || hsm.data2 != null || hsm.data3 != null ||
                hsm.hueDivisions != 0 || hsm.saturationDivisions != 0 || hsm.valueDivisions != 0
            ) return null
            return Entry(snapshot.copied(), priority)
        }

        if (!hsm.valid || hsm.data3 != null) return null
        val data1 = hsm.data1 ?: return null
        if (hsm.hueDivisions <= 0 || hsm.saturationDivisions <= 0 || hsm.valueDivisions <= 0) return null
        val expectedTriplets = hsm.hueDivisions.toLong() * hsm.saturationDivisions.toLong() * hsm.valueDivisions.toLong()
        val expectedFloats = expectedTriplets * 3L
        if (expectedFloats <= 0L || expectedFloats > MAX_ARRAY_FLOATS || data1.size.toLong() != expectedFloats) return null
        if (hsm.data2 != null && hsm.data2.size.toLong() != expectedFloats) return null
        if (data1.any { !it.isFinite() } || hsm.data2?.any { !it.isFinite() } == true) return null
        if (hsm.data2 != null && !hasSecondCharacterization) return null
        return Entry(snapshot.copied(), priority)
    }

    private fun installNative(entry: Entry): Boolean {
        val snapshot = entry.snapshot
        val hsm = snapshot.hueSatMap
        val cm1 = snapshot.colorMatrix1 ?: return false
        val discovery = snapshot.discoveryEffectiveCcm ?: return false
        return runCatching {
            RawCameraColorProfileNativeBridge.nativeInstallProfile(
                profileId = snapshot.calibrationProfileId,
                sourcePriority = entry.priority,
                calibrationIlluminant1 = snapshot.calibrationIlluminant1,
                calibrationIlluminant2 = snapshot.calibrationIlluminant2,
                colorMatrix1 = cm1,
                colorMatrix2 = snapshot.colorMatrix2,
                cameraCalibration1 = snapshot.cameraCalibration1,
                cameraCalibration2 = snapshot.cameraCalibration2,
                forwardMatrix1 = snapshot.forwardMatrix1,
                forwardMatrix2 = snapshot.forwardMatrix2,
                analogBalance = snapshot.analogBalance,
                discoveryEffectiveCcm = discovery,
                hueDivisions = if (hsm.available) hsm.hueDivisions else 0,
                saturationDivisions = if (hsm.available) hsm.saturationDivisions else 0,
                valueDivisions = if (hsm.available) hsm.valueDivisions else 0,
                encoding = if (hsm.available) hsm.encoding else 0,
                hueSatData1 = hsm.data1,
                hueSatData2 = hsm.data2
            )
        }.getOrElse {
            Log.w(TAG, "native profile install not ready/failed for ${snapshot.calibrationProfileId}", it)
            false
        }
    }

    private fun mergeByPriority(target: ConcurrentHashMap<String, Entry>, entry: Entry) {
        target.compute(entry.snapshot.calibrationProfileId) { _, existing ->
            if (existing == null || entry.priority >= existing.priority) entry else existing
        }
    }

    private fun persist(entry: Entry): Boolean {
        val dir = storageDirectory ?: return false.also {
            lastPersistenceError = "session_store_not_initialized"
        }
        val target = File(dir, "${stableFileKey(entry.snapshot.calibrationProfileId)}.bncp")
        val tmp = File(dir, "${target.name}.tmp")
        return runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                writeEntry(out, entry)
            }
            replaceAtomically(tmp, target)
            lastPersistenceError = "none"
            true
        }.getOrElse { failure ->
            tmp.delete()
            lastPersistenceError = "${failure.javaClass.simpleName}:${failure.message ?: "unknown"}"
            Log.e(TAG, "profile persistence failed for ${entry.snapshot.calibrationProfileId}", failure)
            false
        }
    }

    private fun replaceAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun loadPersistedEntries(dir: File): List<Entry> {
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".bncp") }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching {
                    DataInputStream(BufferedInputStream(FileInputStream(file))).use(::readEntry)
                }.onFailure { failure ->
                    lastPersistenceError = "read:${file.name}:${failure.javaClass.simpleName}"
                    Log.w(TAG, "persisted profile rejected ${file.name}", failure)
                }.getOrNull()
                    ?.let { validateEntry(it.snapshot, it.priority) }
            }
    }

    private fun writeEntry(out: DataOutputStream, entry: Entry) {
        val s = entry.snapshot
        val h = s.hueSatMap
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(entry.priority)
        out.writeUTF(s.source)
        out.writeUTF(s.calibrationProfileId)
        out.writeBoolean(s.available)
        out.writeBoolean(s.valid)
        out.writeInt(s.calibrationIlluminant1)
        out.writeInt(s.calibrationIlluminant2)
        writeFloatArray(out, s.colorMatrix1)
        writeFloatArray(out, s.colorMatrix2)
        writeFloatArray(out, s.cameraCalibration1)
        writeFloatArray(out, s.cameraCalibration2)
        writeFloatArray(out, s.forwardMatrix1)
        writeFloatArray(out, s.forwardMatrix2)
        writeFloatArray(out, s.analogBalance)
        out.writeBoolean(s.analogBalanceFromTag)
        writeFloatArray(out, s.discoveryEffectiveCcm)
        out.writeBoolean(h.available)
        out.writeBoolean(h.valid)
        out.writeInt(h.hueDivisions)
        out.writeInt(h.saturationDivisions)
        out.writeInt(h.valueDivisions)
        out.writeInt(h.encoding)
        writeFloatArray(out, h.data1)
        writeFloatArray(out, h.data2)
        writeFloatArray(out, h.data3)
        out.writeUTF(h.status)
        out.writeUTF(s.status)
    }

    private fun readEntry(input: DataInputStream): Entry {
        require(input.readInt() == MAGIC) { "magic_mismatch" }
        require(input.readInt() == VERSION) { "version_mismatch" }
        val priority = input.readInt()
        require(priority == PRIORITY_EXTERNAL || priority == PRIORITY_BNCAM || priority == PRIORITY_OEM) { "priority_invalid" }
        val source = input.readUTF()
        val profileId = input.readUTF()
        val available = input.readBoolean()
        val valid = input.readBoolean()
        val illuminant1 = input.readInt()
        val illuminant2 = input.readInt()
        val colorMatrix1 = readFloatArray(input)
        val colorMatrix2 = readFloatArray(input)
        val calibration1 = readFloatArray(input)
        val calibration2 = readFloatArray(input)
        val forward1 = readFloatArray(input)
        val forward2 = readFloatArray(input)
        val analog = readFloatArray(input) ?: floatArrayOf(1f, 1f, 1f)
        val analogFromTag = input.readBoolean()
        val discovery = readFloatArray(input)
        val hsmAvailable = input.readBoolean()
        val hsmValid = input.readBoolean()
        val hue = input.readInt()
        val saturation = input.readInt()
        val value = input.readInt()
        val encoding = input.readInt()
        val data1 = readFloatArray(input)
        val data2 = readFloatArray(input)
        val data3 = readFloatArray(input)
        val hsmStatus = input.readUTF()
        val status = input.readUTF()
        return Entry(
            DngCameraColorProfileSnapshot(
                available = available,
                valid = valid,
                source = source,
                calibrationProfileId = profileId,
                calibrationIlluminant1 = illuminant1,
                calibrationIlluminant2 = illuminant2,
                colorMatrix1 = colorMatrix1,
                colorMatrix2 = colorMatrix2,
                cameraCalibration1 = calibration1,
                cameraCalibration2 = calibration2,
                forwardMatrix1 = forward1,
                forwardMatrix2 = forward2,
                analogBalance = analog,
                analogBalanceFromTag = analogFromTag,
                discoveryEffectiveCcm = discovery,
                hueSatMap = DngHueSatMapProfileSnapshot(
                    available = hsmAvailable,
                    valid = hsmValid,
                    hueDivisions = hue,
                    saturationDivisions = saturation,
                    valueDivisions = value,
                    encoding = encoding,
                    data1 = data1,
                    data2 = data2,
                    data3 = data3,
                    status = hsmStatus
                ),
                status = status
            ),
            priority
        )
    }

    private fun writeFloatArray(out: DataOutputStream, values: FloatArray?) {
        if (values == null) {
            out.writeInt(-1)
            return
        }
        require(values.size <= MAX_ARRAY_FLOATS) { "array_too_large" }
        out.writeInt(values.size)
        values.forEach(out::writeFloat)
    }

    private fun readFloatArray(input: DataInputStream): FloatArray? {
        val count = input.readInt()
        if (count == -1) return null
        require(count in 0..MAX_ARRAY_FLOATS) { "array_length_invalid:$count" }
        return FloatArray(count) { input.readFloat() }
    }

    private fun stableFileKey(profileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
