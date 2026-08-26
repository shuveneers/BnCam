package com.bncam.core.isp.raw

import com.bncam.core.engine.ImageUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic owner of a native-allocated dense RAW16 payload.
 *
 * The backing memory is exposed to Kotlin only as a direct ByteBuffer under [withDirectBuffer].
 * JPEG-only captures never materialize the complete payload into the managed heap. DNG export may
 * request exactly one explicit ByteArray copy through [materializeForDng].
 *
 * The ownership monitor prevents close/materialize/render races: [close] cannot release the native
 * allocation while a synchronous JNI consumer is using it.
 */
class NativeRaw16Buffer internal constructor(
    private val directBuffer: ByteBuffer,
    val byteCount: Int,
    val width: Int,
    val height: Int,
    val sourceCropLeft: Int = 0,
    val sourceCropTop: Int = 0
) : AutoCloseable {
    private val ownershipMonitor = Any()
    private val closed = AtomicBoolean(false)
    private val managedMaterializations = AtomicInteger(0)
    private val managedMaterializedBytes = AtomicLong(0L)

    init {
        require(directBuffer.isDirect) { "NativeRaw16Buffer requires a direct ByteBuffer." }
        require(byteCount > 0) { "NativeRaw16Buffer byteCount must be positive." }
        require(width > 0 && height > 0) { "NativeRaw16Buffer dimensions must be positive." }
        require(byteCount.toLong() == width.toLong() * height.toLong() * 2L) {
            "Native RAW16 byteCount=$byteCount does not match ${width}x$height dense RAW16."
        }
        require(directBuffer.capacity() >= byteCount) {
            "Native RAW16 capacity ${directBuffer.capacity()} is smaller than $byteCount bytes."
        }
        directBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    val isClosed: Boolean
        get() = closed.get()

    val managedMaterializationCount: Int
        get() = managedMaterializations.get()

    val managedMaterializationBytes: Long
        get() = managedMaterializedBytes.get()

    /**
     * Runs one synchronous native/direct-buffer consumer while retaining ownership.
     * Phase 2 asynchronous GPU work must extend this contract with an explicit completion fence.
     */
    internal fun <T> withDirectBuffer(block: (ByteBuffer) -> T): T = synchronized(ownershipMonitor) {
        check(!closed.get()) { "NativeRaw16Buffer has already been released." }
        block(directBuffer)
    }

    /** The only supported managed-heap materialization boundary, reserved for DNG export/debug. */
    fun materializeForDng(): ByteArray = synchronized(ownershipMonitor) {
        check(!closed.get()) { "NativeRaw16Buffer has already been released." }
        check(managedMaterializations.compareAndSet(0, 1)) {
            "Native RAW16 may be materialized into managed memory at most once."
        }
        try {
            val source = directBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            source.clear()
            source.limit(byteCount)
            ByteArray(byteCount).also { bytes ->
                source.get(bytes)
                managedMaterializedBytes.set(byteCount.toLong())
            }
        } catch (failure: Throwable) {
            managedMaterializations.set(0)
            managedMaterializedBytes.set(0L)
            throw failure
        }
    }

    override fun close() = synchronized(ownershipMonitor) {
        if (closed.compareAndSet(false, true)) {
            ImageUtils.releaseNativeRaw16BufferSafe(directBuffer)
        }
    }
}
