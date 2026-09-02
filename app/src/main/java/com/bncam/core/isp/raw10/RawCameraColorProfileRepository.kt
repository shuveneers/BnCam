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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trusted camera colour-characterization repository with a process-session freeze.
 *
 * The profile set used for rendering is fixed when [beginSession] is first called. Profiles learned
 * from DngCreator during that process are persisted and staged for the next process, never injected
 * into the active native registry mid-session. This prevents capture #1 and capture #2 from using
 * different camera-colour owners solely because capture #1 discovered an OEM HueSatMap.
 *
 * Priority is provenance, not aesthetics: OEM DngCreator > explicit BnCam calibration > external
 * DNG/DCP. No API in this repository can synthesize a HueSatMap from scene statistics or tuning.
 */
object RawCameraColorProfileRepository {
    private const val TAG = "RawCameraColorProfileRepo"
    private const val PRIORITY_OEM = 300
    private const val PRIORITY_BNCAM = 200
    private const val PRIORITY_EXTERNAL = 100
    private const val MAGIC = 0x424E4350 // BNCP
    private const val VERSION = 1
    private const val MAX_ARRAY_FLOATS = (1 shl 20) * 3 // Mirrors native registry hard bound.
    private const val STORE_DIR = "raw_camera_color_profiles_v1"

    private data class Entry(val snapshot: DngCameraColorProfileSnapshot, val priority: Int)

    /** Frozen active-session candidates. */
    private val profiles = ConcurrentHashMap<String, Entry>()
    /** Newly learned profiles that may become active only after a new process begins. */
    private val pendingProfiles = ConcurrentHashMap<String, Entry>()
    /** Profiles already accepted by the native registry during this process. */
    private val nativeInstalled = ConcurrentHashMap.newKeySet<String>()
    private val sessionStarted = AtomicBoolean(false)
    private val sessionNativeReady = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var storageDirectory: File? = null
    @Volatile private var sessionLoadCount: Int = 0
    @Volatile private var lastPersistenceError: String = "none"

    /**
     * Freeze the colour-profile set for this process and preload profiles discovered in an earlier
     * process. Calling this again during navigation/activity recreation is a no-op by design.
     */
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
                "session frozen: persistedCandidates=${profiles.size}; " +
                    "new discoveries will activate next process"
            )
        }
        ensureSessionProfilesInstalled()
    }

    /**
     * Retry native activation of the frozen session set. Safe to call immediately before any known
     * native ISP warm-up; this also covers app startup ordering where libbncam was not loaded yet.
     */
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

    /**
     * OEM discovery during an active session is persisted but never activated in that same process.
     * If no session was started (e.g. a focused host/integration caller), legacy immediate behavior
     * remains available and still passes through the same native validator.
     */
    fun installDiscoveredProfile(snapshot: DngCameraColorProfileSnapshot): Boolean =
        accept(snapshot.copy(source = "OEM_DNGCREATOR"), PRIORITY_OEM)

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
        "sessionStarted=${sessionStarted.get()}; sessionFrozen=true; " +
            "persistedLoaded=$sessionLoadCount; activeProfiles=${profiles.size}; " +
            "nativeInstalled=${nativeInstalled.size}; nativeReady=${sessionNativeReady.get()}; " +
            "pendingNextProcess=${pendingProfiles.size}; persistenceError=$lastPersistenceError"

    private fun accept(snapshot: DngCameraColorProfileSnapshot, priority: Int): Boolean {
        val entry = validateEntry(snapshot, priority) ?: return false
        if (sessionStarted.get()) {
            val existingActive = profiles[entry.snapshot.calibrationProfileId]
            if (existingActive != null && existingActive.priority > priority) return false
            val existingPending = pendingProfiles[entry.snapshot.calibrationProfileId]
            if (existingPending != null && existingPending.priority > priority) return false

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

    private fun validateEntry(snapshot: DngCameraColorProfileSnapshot, priority: Int): Entry? {
        if (!snapshot.available || !snapshot.valid || snapshot.calibrationProfileId.isBlank() ||
            snapshot.calibrationProfileId == "unknown") {
            Log.i(
                TAG,
                "profile rejected: id=${snapshot.calibrationProfileId} source=${snapshot.source} status=${snapshot.status}"
            )
            return null
        }
        val hsm = snapshot.hueSatMap
        val cm1 = snapshot.colorMatrix1 ?: return null
        val discovery = snapshot.discoveryEffectiveCcm ?: return null
        val data1 = hsm.data1 ?: return null
        if (cm1.size != 9 || discovery.size != 9 || snapshot.analogBalance.size < 3) return null
        if (hsm.data3 != null) return null // DELTA 0144: unverified triple characterization rejected.
        if (hsm.hueDivisions <= 0 || hsm.saturationDivisions <= 0 || hsm.valueDivisions <= 0) return null
        val expectedTriplets = hsm.hueDivisions.toLong() * hsm.saturationDivisions.toLong() * hsm.valueDivisions.toLong()
        val expectedFloats = expectedTriplets * 3L
        if (expectedFloats <= 0L || expectedFloats > MAX_ARRAY_FLOATS || data1.size.toLong() != expectedFloats) return null
        if (hsm.data2 != null && hsm.data2.size.toLong() != expectedFloats) return null
        if (data1.any { !it.isFinite() } || hsm.data2?.any { !it.isFinite() } == true) return null
        return Entry(snapshot.copied(), priority)
    }

    private fun installNative(entry: Entry): Boolean {
        val snapshot = entry.snapshot
        val hsm = snapshot.hueSatMap
        val cm1 = snapshot.colorMatrix1 ?: return false
        val discovery = snapshot.discoveryEffectiveCcm ?: return false
        val data1 = hsm.data1 ?: return false
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
                hueDivisions = hsm.hueDivisions,
                saturationDivisions = hsm.saturationDivisions,
                valueDivisions = hsm.valueDivisions,
                encoding = hsm.encoding,
                hueSatData1 = data1,
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
