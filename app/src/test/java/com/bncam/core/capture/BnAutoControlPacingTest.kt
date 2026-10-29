package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BnAutoControlPacingTest {
    @Test fun `metering is bounded but never blocks a new lens generation`() {
        val gate = BnAutoControlPacing()
        assertTrue(gate.shouldMeter("g1:main", 1_000_000_000L))
        assertFalse(gate.shouldMeter("g1:main", 1_033_000_000L))
        assertFalse(gate.shouldMeter("g1:main", 1_100_000_000L))
        assertTrue(gate.shouldMeter("g1:main", 1_166_000_000L))
        assertFalse(gate.shouldMeter("g1:main", 1_166_000_000L))
        assertTrue(gate.shouldMeter("g2:tele", 1_010_000_000L))
    }

    @Test fun `minor flicker driven target noise does not resubmit requests`() {
        val gate = BnAutoControlPacing()
        assertTrue(gate.shouldSubmit("main", 1_000_000_000L, 5_000_000L, 2000,
            5_000_000L, 2400, "NONE"))
        assertFalse(gate.shouldSubmit("main", 1_166_000_000L, 5_000_000L, 2400,
            5_000_000L, 2700, "NONE"))
        assertFalse(gate.shouldSubmit("main", 1_400_000_000L, 5_000_000L, 2400,
            5_000_000L, 2560, "NONE"))
        assertTrue(gate.shouldSubmit("main", 1_500_000_000L, 5_000_000L, 2400,
            5_000_000L, 3200, "NONE"))
    }

    @Test fun `physical motion can shorten shutter quickly while focus is untouched`() {
        val gate = BnAutoControlPacing()
        assertTrue(gate.shouldSubmit("main", 1_000_000_000L, 10_000_000L, 800,
            8_000_000L, 1000, "LENS_STABILITY_FALLBACK"))
        assertFalse(gate.shouldSubmit("main", 1_060_000_000L, 8_000_000L, 1000,
            3_000_000L, 2700, "SUBJECT_MOTION"))
        assertTrue(gate.shouldSubmit("main", 1_116_000_000L, 8_000_000L, 1000,
            3_000_000L, 2700, "SUBJECT_MOTION"))
    }

    @Test fun `large scene transition is not held for full normal settling interval`() {
        val gate = BnAutoControlPacing()
        assertTrue(gate.shouldSubmit("main", 1_000_000_000L, 5_000_000L, 400,
            5_000_000L, 800, "NONE"))
        assertFalse(gate.shouldSubmit("main", 1_110_000_000L, 5_000_000L, 800,
            5_000_000L, 1600, "NONE"))
        assertTrue(gate.shouldSubmit("main", 1_160_000_000L, 5_000_000L, 800,
            5_000_000L, 1600, "NONE"))
    }

    @Test fun `switching camera resets submission hold`() {
        val gate = BnAutoControlPacing()
        assertTrue(gate.shouldSubmit("main", 2_000_000_000L, 10_000_000L, 800,
            8_000_000L, 1000, "NONE"))
        assertTrue(gate.shouldSubmit("tele", 1_000_000_000L, 10_000_000L, 800,
            8_000_000L, 1000, "NONE"))
    }

    @Test fun `observed thirty fps allows metering every two raw frames`() {
        val gate = BnAutoControlPacing()
        val key = "main:raw"
        val base = 1_000_000_000L
        gate.observeFrame(key, base, 33_333_333L)
        assertTrue(gate.shouldMeter(key, base))
        gate.observeFrame(key, base + 33_333_333L, 33_333_333L)
        assertFalse(gate.shouldMeter(key, base + 33_333_333L))
        gate.observeFrame(key, base + 66_666_666L, 33_333_333L)
        assertTrue(gate.shouldMeter(key, base + 66_666_666L))
    }

    @Test fun `large scene changes submit within about three measured frames`() {
        val gate = BnAutoControlPacing()
        val key = "main:raw"
        val base = 1_000_000_000L
        gate.observeFrame(key, base, 33_333_333L)
        assertTrue(gate.shouldSubmit(key, base, 5_000_000L, 400, 5_000_000L, 800, "NONE"))
        assertFalse(gate.shouldSubmit(key, base + 20_000_000L,
            5_000_000L, 800, 5_000_000L, 1600, "NONE"))
        assertTrue(gate.shouldSubmit(key, base + 100_000_000L,
            5_000_000L, 800, 5_000_000L, 1600, "NONE"))
    }

    @Test fun `missing frame metadata falls back safely and a new lens resets pace`() {
        val gate = BnAutoControlPacing()
        val start = 2_000_000_000L
        gate.observeFrame("main", start, null)
        assertTrue(gate.shouldMeter("main", start))
        assertFalse(gate.shouldMeter("main", start + 66_000_000L))
        gate.observeFrame("main", start + 33_333_333L, null)
        gate.observeFrame("main", start + 66_666_666L, null)
        assertTrue(gate.shouldMeter("main", start + 66_666_666L))
        assertTrue(gate.measuredFramePeriodNs("main")!! in 33_000_000L..34_000_000L)
        assertTrue(gate.shouldMeter("tele", 500_000_000L))
        assertTrue(gate.measuredFramePeriodNs("tele") == null)
    }

    @Test fun `dropped frames do not falsely slow controller when sensor metadata is available`() {
        val gate = BnAutoControlPacing()
        gate.observeFrame("main", 1_000_000_000L, 33_333_333L)
        gate.observeFrame("main", 1_166_666_665L, 33_333_333L)
        assertTrue(gate.measuredFramePeriodNs("main")!! < 40_000_000L)
    }

    @Test fun `pending target is acknowledged only by actual sensor timing and gain`() {
        val p = BnAutoControlPacing()
        val t = 1_000_000_000L
        assertTrue(p.shouldSubmit("main", t, 5_000_000L, 400, 10_000_000L, 200, "NONE"))
        p.expectRealization("main", t, 10_000_000L, 200)
        assertEquals("PENDING", p.observeRealized("main", t + 33_000_000L, 5_000_000L, 400))
        assertFalse(p.shouldSubmit("main", t + 100_000_000L, 10_000_000L, 200,
            5_000_000L, 400, "NONE"))
        assertEquals("ACKNOWLEDGED", p.observeRealized("main", t + 133_000_000L, 10_000_000L, 200))
    }

    @Test fun `unrealized sensor request times out rather than freezing AE forever`() {
        val p = BnAutoControlPacing()
        val t = 1_000_000_000L
        p.expectRealization("main", t, 10_000_000L, 200)
        assertEquals("TIMEOUT_SENSOR_DID_NOT_MATCH", p.observeRealized("main",
            t + 400_000_000L, 5_000_000L, 400))
        assertTrue(p.shouldSubmit("main", t + 400_000_000L,
            5_000_000L, 400, 10_000_000L, 200, "NONE"))
    }

    @Test fun `lens change retires the old in flight target`() {
        val p = BnAutoControlPacing()
        val t = 1_000_000_000L
        p.expectRealization("main", t, 10_000_000L, 200)
        assertEquals("NONE", p.realizationStatus("tele"))
        assertTrue(p.shouldSubmit("tele", t + 33_000_000L,
            5_000_000L, 400, 10_000_000L, 200, "NONE"))
    }
}
