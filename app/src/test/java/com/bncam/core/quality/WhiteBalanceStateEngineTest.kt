package com.bncam.core.quality

import kotlin.math.abs
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class WhiteBalanceStateEngineTest {
    @Test
    fun `startup does not publish a single unvalidated sample`() {
        val engine = WhiteBalanceStateEngine()
        assertNull(engine.observe("lens-0", 1, floatArrayOf(0.8f, 1f, 1f, 3.8f), WhiteBalanceConvergence.SEARCHING))
        assertNull(engine.observe("lens-0", 1, floatArrayOf(2.0f, 1f, 1f, 1.5f), WhiteBalanceConvergence.SEARCHING))
        assertNull(engine.snapshot("lens-0"))
    }

    @Test
    fun `three consistent observations publish a stable solution`() {
        val engine = WhiteBalanceStateEngine()
        val a = floatArrayOf(2.00f, 1f, 1f, 1.50f)
        val b = floatArrayOf(2.04f, 1f, 1f, 1.47f)
        val c = floatArrayOf(2.02f, 1f, 1f, 1.49f)
        assertNull(engine.observe("lens-0", 1, a, WhiteBalanceConvergence.SEARCHING))
        assertNull(engine.observe("lens-0", 1, b, WhiteBalanceConvergence.CONVERGED))
        val stable = engine.observe("lens-0", 1, c, WhiteBalanceConvergence.CONVERGED)
        assertNotNull(stable)
        val stableSnapshot = requireNotNull(stable)
        assertTrue(stableSnapshot.confidence >= 0.60f)
    }

    @Test
    fun `same lens generation change retains stable state`() {
        val engine = WhiteBalanceStateEngine()
        repeat(3) {
            engine.observe("lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f), WhiteBalanceConvergence.CONVERGED)
        }
        val before = engine.snapshot("lens-0")
        assertNotNull(before)
        val beforeSnapshot = requireNotNull(before)
        val after = engine.observe(
            "lens-0",
            2,
            floatArrayOf(2.05f, 1f, 1f, 1.48f),
            WhiteBalanceConvergence.SEARCHING
        )
        assertNotNull(after)
        val afterSnapshot = requireNotNull(after)
        assertEquals(2, afterSnapshot.pipelineGeneration)
        assertTrue(abs(afterSnapshot.gains[0] - beforeSnapshot.gains[0]) < 0.10f)
    }

    @Test
    fun `new physical lens does not inherit another lens gains`() {
        val engine = WhiteBalanceStateEngine()
        repeat(3) {
            engine.observe("lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f), WhiteBalanceConvergence.CONVERGED)
        }
        assertNotNull(engine.snapshot("lens-0"))
        assertNull(engine.observe("lens-1", 2, floatArrayOf(1.2f, 1f, 1f, 2.4f), WhiteBalanceConvergence.SEARCHING))
        assertNull(engine.snapshot("lens-1"))
    }

    @Test
    fun `stable gains keep adapting after convergence without an explicit lock`() {
        val engine = WhiteBalanceStateEngine()
        repeat(3) {
            engine.observe("lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f), WhiteBalanceConvergence.CONVERGED)
        }
        val before = requireNotNull(engine.snapshot("lens-0"))
        repeat(5) {
            engine.observe("lens-0", 1, floatArrayOf(1.2f, 1f, 1f, 2.5f), WhiteBalanceConvergence.CONVERGED)
        }
        val after = requireNotNull(engine.snapshot("lens-0"))
        assertTrue(abs(after.gains[0] - before.gains[0]) > 0.01f)
        assertTrue(abs(after.gains[3] - before.gains[3]) > 0.01f)
    }
}
