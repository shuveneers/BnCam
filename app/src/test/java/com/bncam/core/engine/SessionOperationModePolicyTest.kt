package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionOperationModePolicyTest {
    @Test
    fun `no explicit or known mode resolves regular`() {
        val resolved = SessionOperationModePolicy.resolve(SessionOperationModePolicyInput())
        assertEquals(OperationModePolicyKind.REGULAR, resolved.policy)
        assertEquals(0, resolved.operationMode)
    }

    @Test
    fun `explicit vendor mode resolves manual custom`() {
        val resolved = SessionOperationModePolicy.resolve(
            SessionOperationModePolicyInput(explicitOperationModes = listOf(0x8004))
        )
        assertEquals(OperationModePolicyKind.MANUAL_CUSTOM, resolved.policy)
        assertEquals(0x8004, resolved.operationMode)
    }

    @Test
    fun `explicit zero remains regular`() {
        val resolved = SessionOperationModePolicy.resolve(
            SessionOperationModePolicyInput(explicitOperationModes = listOf(0))
        )
        assertEquals(OperationModePolicyKind.REGULAR, resolved.policy)
        assertEquals(0, resolved.operationMode)
    }

    @Test
    fun `known device evidence resolves known device mode`() {
        val resolved = SessionOperationModePolicy.resolve(
            SessionOperationModePolicyInput(
                knownDeviceOperationMode = 0x8007,
                knownDeviceEvidence = "qualified device table"
            )
        )
        assertEquals(OperationModePolicyKind.KNOWN_DEVICE_MODE, resolved.policy)
        assertEquals(0x8007, resolved.operationMode)
        assertEquals("qualified device table", resolved.evidence)
    }

    @Test
    fun `explicit manual mode overrides known device mapping`() {
        val resolved = SessionOperationModePolicy.resolve(
            SessionOperationModePolicyInput(
                explicitOperationModes = listOf(0x8009),
                knownDeviceOperationMode = 0x8002,
                knownDeviceEvidence = "device table"
            )
        )
        assertEquals(OperationModePolicyKind.MANUAL_CUSTOM, resolved.policy)
        assertEquals(0x8009, resolved.operationMode)
    }

    @Test
    fun `conflicting explicit modes are rejected instead of selecting first`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionOperationModePolicy.resolve(
                SessionOperationModePolicyInput(explicitOperationModes = listOf(0x8001, 0x8002))
            )
        }
    }

    @Test
    fun `non vendor nonzero explicit mode is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionOperationModePolicy.resolve(
                SessionOperationModePolicyInput(explicitOperationModes = listOf(12))
            )
        }
    }

    @Test
    fun `invalid known device mapping is rejected instead of probed around`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionOperationModePolicy.resolve(
                SessionOperationModePolicyInput(knownDeviceOperationMode = 42)
            )
        }
    }
}
