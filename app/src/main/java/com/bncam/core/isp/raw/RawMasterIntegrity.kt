package com.bncam.core.isp.raw

import java.nio.ByteBuffer
import java.util.zip.CRC32

data class RawMasterFingerprint(val byteCount: Int, val crc32: Long)

object RawMasterIntegrity {
    fun fingerprint(payload: ByteArray): RawMasterFingerprint {
        val crc = CRC32()
        crc.update(payload)
        return RawMasterFingerprint(payload.size, crc.value)
    }

    fun fingerprint(payload: ByteBuffer, byteCount: Int): RawMasterFingerprint {
        require(payload.isDirect) { "RAW16 integrity checks require a direct native buffer." }
        require(byteCount in 1..payload.capacity()) { "Invalid RAW16 integrity byte count: $byteCount" }
        val source = payload.duplicate()
        source.clear()
        source.limit(byteCount)
        val crc = CRC32()
        crc.update(source)
        return RawMasterFingerprint(byteCount, crc.value)
    }

    fun isUnchanged(payload: ByteArray, fingerprint: RawMasterFingerprint): Boolean {
        return payload.size == fingerprint.byteCount && fingerprint(payload).crc32 == fingerprint.crc32
    }

    fun isUnchanged(payload: ByteBuffer, byteCount: Int, fingerprint: RawMasterFingerprint): Boolean {
        return byteCount == fingerprint.byteCount && fingerprint(payload, byteCount).crc32 == fingerprint.crc32
    }
}
