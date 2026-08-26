package com.bncam.core.isp.raw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMasterIntegrityTest {
    @Test
    fun derivedJpegWorkingCopyCannotMutateMaster() {
        val master = ByteArray(4096) { index -> (index * 31).toByte() }
        val fingerprint = RawMasterIntegrity.fingerprint(master)
        val jpegWorkingCopy = master.copyOf()

        jpegWorkingCopy[0] = (jpegWorkingCopy[0].toInt() xor 0x7f).toByte()

        assertTrue(RawMasterIntegrity.isUnchanged(master, fingerprint))
        assertFalse(RawMasterIntegrity.isUnchanged(jpegWorkingCopy, fingerprint))
    }
}
