package com.bncam.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OisVendorKeyPolicyTest {
    @Test
    fun acceptsExplicitOpticalOisKeys() {
        assertTrue(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.camera.ois.mode"))
        assertTrue(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.optical_stabilization.enable"))
    }

    @Test
    fun rejectsDigitalStabilizationKeys() {
        assertFalse(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.camera.eis.enable"))
        assertFalse(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.video.ois.mode"))
        assertFalse(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.preview.stabilization.mode"))
        assertFalse(OisVendorKeyPolicy.isOpticalOnlyKey("vendor.digital.stabilization.enable"))
    }
}
