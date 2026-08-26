package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableLensKeyTest {

    @Test
    fun testStableLensKeyFromRawPhysicalId() {
        val key1 = StableLensKey.fromRawPhysicalId("0", "0")
        val key2 = StableLensKey.fromRawPhysicalId("0", "none")
        val key3 = StableLensKey.fromRawPhysicalId("0")

        assertTrue(key1.value.startsWith("lens_v2_"))
        assertTrue(key2.value.startsWith("lens_v2_"))
        assertEquals(key2.value, key3.value)
        assertNotEquals(key1.value, key2.value)
    }

    @Test
    fun testStableLensKeyFromStringIdempotence() {
        val raw = "0"
        val stable1 = StableLensKey.fromString(raw)
        val stable2 = StableLensKey.fromString(stable1.value)

        assertEquals(stable1.value, stable2.value)
        assertTrue(stable1.value.startsWith("lens_v2_"))
        assertTrue(stable2.value.startsWith("lens_v2_"))
    }
}
