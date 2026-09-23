package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalSensorExposureAuthorityPolicyTest {
    @Test
    fun standardAutoAlwaysBelongsToCamera2() {
        val decision = PhysicalSensorExposureAuthorityPolicy.resolve(
            explicitManualSensorRequest = false,
            profileRequiresExplicitExposurePriority = false
        )
        assertEquals(PhysicalSensorExposureOwner.CAMERA2_HAL_AE, decision.owner)
        assertTrue(decision.camera2OwnsCompleteExposure)
    }

    @Test
    fun explicitProfilePriorityCanTakeOwnership() {
        val decision = PhysicalSensorExposureAuthorityPolicy.resolve(
            explicitManualSensorRequest = false,
            profileRequiresExplicitExposurePriority = true
        )
        assertEquals(PhysicalSensorExposureOwner.PROFILE_EXPLICIT_PRIORITY, decision.owner)
    }

    @Test
    fun explicitManualSensorRequestHasHighestAuthority() {
        val decision = PhysicalSensorExposureAuthorityPolicy.resolve(
            explicitManualSensorRequest = true,
            profileRequiresExplicitExposurePriority = true
        )
        assertEquals(PhysicalSensorExposureOwner.USER_MANUAL_SENSOR, decision.owner)
    }
}
