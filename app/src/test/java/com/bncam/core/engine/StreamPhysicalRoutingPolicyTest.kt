package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPhysicalRoutingPolicyTest {
    @Test
    fun `inherit active route keeps selected physical child per role`() {
        val plan = StreamPhysicalRoutingPolicy.resolve(
            logicalCameraId = "0",
            advertisedPhysicalCameraIds = setOf("2", "3"),
            activePhysicalCameraId = "2",
            roleRequests = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.inheritActive(),
                BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive()
            )
        )

        assertEquals("2", plan.physicalCameraIdFor(BnCamStreamRoleIds.VIEWFINDER))
        assertEquals("2", plan.physicalCameraIdFor(BnCamStreamRoleIds.PRIMARY_BUFFER))
        assertEquals(setOf("2"), plan.routedPhysicalCameraIds)
    }

    @Test
    fun `inherit active route falls back only to logical when no physical child was selected`() {
        val plan = StreamPhysicalRoutingPolicy.resolve(
            logicalCameraId = "0",
            advertisedPhysicalCameraIds = setOf("2", "3"),
            activePhysicalCameraId = null,
            roleRequests = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.inheritActive()
            )
        )

        assertNull(plan.physicalCameraIdFor(BnCamStreamRoleIds.VIEWFINDER))
        assertTrue(plan.routedPhysicalCameraIds.isEmpty())
    }

    @Test
    fun `one logical session can route outputs independently`() {
        val plan = StreamPhysicalRoutingPolicy.resolve(
            logicalCameraId = "0",
            advertisedPhysicalCameraIds = setOf("2", "3"),
            activePhysicalCameraId = "2",
            roleRequests = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.logical(),
                BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive(),
                BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT to StreamRolePhysicalRouteRequest.physical("3")
            )
        )

        assertNull(plan.physicalCameraIdFor(BnCamStreamRoleIds.VIEWFINDER))
        assertEquals("2", plan.physicalCameraIdFor(BnCamStreamRoleIds.PRIMARY_BUFFER))
        assertEquals("3", plan.physicalCameraIdFor(BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT))
        assertEquals(setOf("2", "3"), plan.routedPhysicalCameraIds)
    }

    @Test
    fun `non advertised active physical route fails instead of inventing fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamPhysicalRoutingPolicy.resolve(
                logicalCameraId = "0",
                advertisedPhysicalCameraIds = setOf("2"),
                activePhysicalCameraId = "7",
                roleRequests = mapOf(
                    BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive()
                )
            )
        }
    }

    @Test
    fun `non advertised explicit role route fails instead of falling back`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamPhysicalRoutingPolicy.resolve(
                logicalCameraId = "0",
                advertisedPhysicalCameraIds = setOf("2", "3"),
                activePhysicalCameraId = "2",
                roleRequests = mapOf(
                    BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT to StreamRolePhysicalRouteRequest.physical("9")
                )
            )
        }
    }

    @Test
    fun `logical camera id cannot be misused as physical child`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamPhysicalRoutingPolicy.resolve(
                logicalCameraId = "0",
                advertisedPhysicalCameraIds = setOf("2"),
                activePhysicalCameraId = null,
                roleRequests = mapOf(
                    BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.physical("0")
                )
            )
        }
    }

    @Test
    fun `summary is role specific rather than session wide`() {
        val plan = StreamPhysicalRoutingPolicy.resolve(
            logicalCameraId = "0",
            advertisedPhysicalCameraIds = setOf("2"),
            activePhysicalCameraId = "2",
            roleRequests = mapOf(
                BnCamStreamRoleIds.VIEWFINDER to StreamRolePhysicalRouteRequest.logical(),
                BnCamStreamRoleIds.PRIMARY_BUFFER to StreamRolePhysicalRouteRequest.inheritActive()
            )
        )

        val summary = plan.summary()
        assertTrue(summary.contains("VIEWFINDER->logical[LOGICAL_CAMERA]"))
        assertTrue(summary.contains("PRIMARY_BUFFER->2[INHERIT_ACTIVE_ROUTE]"))
    }
}
