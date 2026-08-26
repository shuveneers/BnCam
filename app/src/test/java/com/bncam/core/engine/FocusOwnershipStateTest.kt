package com.bncam.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusOwnershipStateTest {
    @Test
    fun facePriorityOnlyOwnsFromIdleFamily() {
        assertTrue(FocusOwnershipState(FocusOwner.AUTO).facePriorityMayOwn)
        assertTrue(FocusOwnershipState(FocusOwner.FACE_PRIORITY).facePriorityMayOwn)
        assertFalse(FocusOwnershipState(FocusOwner.TAP).facePriorityMayOwn)
        assertFalse(FocusOwnershipState(FocusOwner.TRACK_TIMED).facePriorityMayOwn)
        assertFalse(FocusOwnershipState(FocusOwner.MANUAL).facePriorityMayOwn)
    }

    @Test
    fun explicitUserOwnersAreUnambiguous() {
        assertTrue(FocusOwnershipState(FocusOwner.TAP).explicitUserOwner)
        assertTrue(FocusOwnershipState(FocusOwner.TRACK_PINNED).explicitUserOwner)
        assertTrue(FocusOwnershipState(FocusOwner.MANUAL).explicitUserOwner)
        assertTrue(FocusOwnershipState(FocusOwner.AE_AF_LOCK).explicitUserOwner)
        assertFalse(FocusOwnershipState(FocusOwner.AUTO).explicitUserOwner)
        assertFalse(FocusOwnershipState(FocusOwner.FACE_PRIORITY).explicitUserOwner)
    }
}
