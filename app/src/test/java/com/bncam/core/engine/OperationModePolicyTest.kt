package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationModePolicyTest {
    @Test
    fun `regular operation mode selects regular session`() {
        val resolved = ResolvedOperationMode.regular()
        assertEquals(0, resolved.operationMode)
        assertEquals(StreamSessionMode.REGULAR_SESSION, resolved.streamMode)
    }

    @Test
    fun `known device mode requires evidence and selects custom session`() {
        val resolved = ResolvedOperationMode.knownDevice(
            operationMode = 0x8002,
            evidence = "runtime-qualified device profile"
        )
        assertEquals(OperationModePolicyKind.KNOWN_DEVICE_MODE, resolved.policy)
        assertEquals(StreamSessionMode.CUSTOM_OPERATION_MODE_SESSION, resolved.streamMode)
    }

    @Test
    fun `manual custom mode selects custom session`() {
        val resolved = ResolvedOperationMode.manual(0x8004)
        assertEquals(OperationModePolicyKind.MANUAL_CUSTOM, resolved.policy)
        assertEquals(StreamSessionMode.CUSTOM_OPERATION_MODE_SESSION, resolved.streamMode)
    }

    @Test
    fun `regular policy rejects non-zero vendor mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedOperationMode(OperationModePolicyKind.REGULAR, 0x8002)
        }
    }

    @Test
    fun `known device policy rejects missing evidence`() {
        assertThrows(IllegalStateException::class.java) {
            ResolvedOperationMode.knownDevice(0x8002, "   ")
        }
    }
}
