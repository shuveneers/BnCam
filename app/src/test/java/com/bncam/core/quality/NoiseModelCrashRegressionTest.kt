package com.bncam.core.quality

import com.bncam.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseModelCrashRegressionTest {

    @Test
    fun testCfaStateInvariants() {
        val loading: CfaState = CfaState.Loading
        val unavailable: CfaState = CfaState.Unavailable("metadata missing")
        val unsupported: CfaState = CfaState.Unsupported("MONO")

        assertTrue(loading is CfaState.Loading)
        assertTrue(unavailable is CfaState.Unavailable)
        assertTrue(unsupported is CfaState.Unsupported)

        val desc = CfaArrangementDescriptor.from(3) // BGGR
        val available: CfaState = CfaState.Available(desc)
        assertTrue(available is CfaState.Available)
        assertEquals("BGGR", (available as CfaState.Available).descriptor.cfaName)
    }

    @Test
    fun testRouteEncodingRoundTrip() {
        val testCases = listOf(
            "0",
            "lens/0",
            "lens:0",
            "100%_pure",
            "camera_öäü",
            "%20already_encoded%20"
        )

        testCases.forEach { raw ->
            val encoded = Routes.encodeRouteArg(raw)
            val decoded = Routes.parseRouteArg(encoded)
            assertFalse(encoded.contains('/'))
            assertEquals(raw, decoded)
        }
    }
}
