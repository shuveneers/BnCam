package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class OisRoutePolicyTest {
    @Test
    fun everyDirectlyOpenedLensUsesItsOwnStandardOisWhenWritable() {
        for (lensId in listOf("2", "3", "4")) {
            assertEquals(
                "lens=$lensId",
                OisRoutePolicy.Route.DIRECT_STANDARD,
                OisRoutePolicy.resolve(
                    userRequested = true,
                    directDeviceRoute = true,
                    hiddenPhysicalRoute = false,
                    selectedLensSupportsOis = true,
                    openedCameraSupportsStandardOis = true,
                    probedDirectStandardOisWritable = false,
                    approvedVendorKeyAvailable = false
                )
            )
        }
    }

    @Test
    fun directLensCanUseStrictOpticalVendorFallbackWithoutEis() {
        assertEquals(
            OisRoutePolicy.Route.DIRECT_VENDOR_OPTICAL,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = true,
                hiddenPhysicalRoute = false,
                selectedLensSupportsOis = false,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = true
            )
        )
    }

    @Test
    fun hiddenPhysicalPrefersOpenedLogicalStandardOis() {
        assertEquals(
            OisRoutePolicy.Route.HIDDEN_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = true,
                selectedLensSupportsOis = true,
                openedCameraSupportsStandardOis = true,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )

        // Even when a per-physical override is advertised, the opened logical standard request is
        // the preferred route. It is the portable path used by working logical/physical apps.
        assertEquals(
            OisRoutePolicy.Route.HIDDEN_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = true,
                selectedLensSupportsOis = true,
                openedCameraSupportsStandardOis = true,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )
    }

    @Test
    fun hiddenPhysicalUsesSelectedLensOisEvenWhenLogicalParentUnderReportsIt() {
        assertEquals(
            OisRoutePolicy.Route.HIDDEN_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = true,
                selectedLensSupportsOis = true,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )
        assertEquals(
            OisRoutePolicy.Route.HIDDEN_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = true,
                selectedLensSupportsOis = true,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )
    }

    @Test
    fun hiddenPhysicalDoesNotTrustUnderReportedOisCapability() {
        assertEquals(
            OisRoutePolicy.Route.HIDDEN_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = true,
                selectedLensSupportsOis = false,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )
    }

    @Test
    fun unresolvedNonDirectRouteStillDoesNotGuessOis() {
        assertEquals(
            OisRoutePolicy.Route.UNAVAILABLE,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = false,
                hiddenPhysicalRoute = false,
                selectedLensSupportsOis = false,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = false,
                approvedVendorKeyAvailable = false
            )
        )
    }

    @Test
    fun hiddenDirectCameraCanRuntimeProbeWritableStandardOis() {
        assertEquals(
            OisRoutePolicy.Route.PROBED_DIRECT_STANDARD,
            OisRoutePolicy.resolve(
                userRequested = true,
                directDeviceRoute = true,
                hiddenPhysicalRoute = false,
                selectedLensSupportsOis = false,
                openedCameraSupportsStandardOis = false,
                probedDirectStandardOisWritable = true,
                approvedVendorKeyAvailable = false
            )
        )
    }
}
