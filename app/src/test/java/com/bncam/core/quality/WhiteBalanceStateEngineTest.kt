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
    @Test
    fun `camera2 gain and matrix remain one temporal color pair`() {
        val engine = WhiteBalanceStateEngine()
        val matrixA = floatArrayOf(
            1.00f, 0.02f, 0.00f,
            0.01f, 0.98f, 0.01f,
            0.00f, 0.03f, 0.97f
        )
        val matrixB = floatArrayOf(
            0.82f, 0.12f, 0.03f,
            0.05f, 0.92f, 0.04f,
            0.02f, 0.08f, 0.84f
        )
        repeat(3) {
            engine.observe(
                "lens-0",
                1,
                floatArrayOf(2.0f, 1f, 1f, 1.5f),
                WhiteBalanceConvergence.CONVERGED,
                matrixA
            )
        }
        val before = requireNotNull(engine.snapshot("lens-0"))
        val after = requireNotNull(
            engine.observe(
                "lens-0",
                1,
                floatArrayOf(1.2f, 1f, 1f, 2.5f),
                WhiteBalanceConvergence.CONVERGED,
                matrixB
            )
        )
        val beforeMatrix = requireNotNull(before.copyColorMatrix())
        val afterMatrix = requireNotNull(after.copyColorMatrix())
        assertTrue(after.gains[0] < before.gains[0])
        assertTrue(after.gains[0] > 1.2f)
        assertTrue(afterMatrix[0] < beforeMatrix[0])
        assertTrue(afterMatrix[0] > matrixB[0])
        assertTrue(after.temporalDelta > 0f)
    }

    @Test
    fun `scene change acceleration is armed only after stable history`() {
        val engine = WhiteBalanceStateEngine()
        repeat(3) {
            engine.observe(
                "lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f),
                WhiteBalanceConvergence.CONVERGED
            )
        }
        repeat(20) {
            engine.observe(
                "lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f),
                WhiteBalanceConvergence.CONVERGED
            )
        }
        val firstShift = requireNotNull(
            engine.observe(
                "lens-0", 1, floatArrayOf(1.1f, 1f, 1f, 2.7f),
                WhiteBalanceConvergence.CONVERGED
            )
        )
        assertTrue(!firstShift.sceneChangeDetected)
        val secondShift = requireNotNull(
            engine.observe(
                "lens-0", 1, floatArrayOf(1.1f, 1f, 1f, 2.7f),
                WhiteBalanceConvergence.CONVERGED
            )
        )
        assertTrue(secondShift.sceneChangeDetected)
    }

    @Test
    fun `physical evidence cannot replace Camera2 bootstrap`() {
        val engine = WhiteBalanceStateEngine()
        val matrix = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        assertNull(
            engine.observePhysical(
                scopeKey = "lens-0",
                pipelineGeneration = 1,
                finalGains = floatArrayOf(1.4f, 1f, 1f, 2.2f),
                colorMatrix = matrix,
                confidence = 0.9f,
                dataAuthority = 0.8f,
                neutralSupport = 0.8f,
                mixedLightScore = 0.0f,
                priorDisagreement = 0.2f,
                validTileCount = 48,
                dataReady = true,
                sensorTimestampNs = 10L
            )
        )
        assertNull(engine.snapshot("lens-0"))
    }

    @Test
    fun `physical evidence becomes bounded target after Camera2 bootstrap`() {
        val engine = WhiteBalanceStateEngine()
        val matrix = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        repeat(3) {
            engine.observe(
                "lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f),
                WhiteBalanceConvergence.CONVERGED, matrix, (it + 1).toLong()
            )
        }
        val before = requireNotNull(engine.snapshot("lens-0"))
        val after = requireNotNull(
            engine.observePhysical(
                "lens-0", 1, floatArrayOf(1.5f, 1f, 1f, 2.1f), matrix,
                confidence = 0.85f,
                dataAuthority = 0.75f,
                neutralSupport = 0.7f,
                mixedLightScore = 0.05f,
                priorDisagreement = 0.2f,
                validTileCount = 40,
                dataReady = true,
                sensorTimestampNs = 20L
            )
        )
        assertEquals(WhiteBalanceObservationSource.PHYSICAL_SCENE, after.source)
        assertTrue(after.gains[0] < before.gains[0])
        assertTrue(after.gains[0] > 1.5f)
        assertTrue(after.gains[3] > before.gains[3])
        assertTrue(after.gains[3] < 2.1f)
        assertEquals(20L, after.sensorTimestampNs)
    }

    @Test
    fun `fresh physical authority prevents Camera2 color pair tug of war`() {
        val engine = WhiteBalanceStateEngine()
        val matrixA = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val matrixB = floatArrayOf(
            0.7f, 0.2f, 0.1f,
            0.1f, 0.8f, 0.1f,
            0.1f, 0.2f, 0.7f
        )
        repeat(3) {
            engine.observe(
                "lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f),
                WhiteBalanceConvergence.CONVERGED, matrixA
            )
        }
        requireNotNull(
            engine.observePhysical(
                "lens-0", 1, floatArrayOf(1.5f, 1f, 1f, 2.1f), matrixA,
                confidence = 0.85f,
                dataAuthority = 0.75f,
                neutralSupport = 0.7f,
                mixedLightScore = 0.05f,
                priorDisagreement = 0.2f,
                validTileCount = 40,
                dataReady = true,
                sensorTimestampNs = 20L
            )
        )
        val physical = requireNotNull(engine.snapshot("lens-0"))
        val physicalMatrix = requireNotNull(physical.copyColorMatrix())
        repeat(20) {
            engine.observe(
                "lens-0", 1, floatArrayOf(2.4f, 1f, 1f, 1.2f),
                WhiteBalanceConvergence.CONVERGED, matrixB
            )
        }
        val held = requireNotNull(engine.snapshot("lens-0"))
        val heldMatrix = requireNotNull(held.copyColorMatrix())
        assertTrue(abs(held.gains[0] - physical.gains[0]) < 1.0e-6f)
        assertTrue(abs(held.gains[3] - physical.gains[3]) < 1.0e-6f)
        assertTrue(abs(heldMatrix[0] - physicalMatrix[0]) < 1.0e-6f)
        assertEquals(WhiteBalanceObservationSource.PHYSICAL_SCENE, held.source)
    }

    @Test
    fun `mixed light damps physical movement authority`() {
        fun preparedEngine(): WhiteBalanceStateEngine = WhiteBalanceStateEngine().also { engine ->
            repeat(3) {
                engine.observe(
                    "lens-0", 1, floatArrayOf(2f, 1f, 1f, 1.5f),
                    WhiteBalanceConvergence.CONVERGED
                )
            }
        }
        val clean = preparedEngine()
        val mixed = preparedEngine()
        val target = floatArrayOf(1.4f, 1f, 1f, 2.2f)
        val cleanAfter = requireNotNull(
            clean.observePhysical(
                "lens-0", 1, target, null, 0.85f, 0.8f, 0.75f,
                0.02f, 0.1f, 44, true, 30L
            )
        )
        val mixedAfter = requireNotNull(
            mixed.observePhysical(
                "lens-0", 1, target, null, 0.85f, 0.8f, 0.75f,
                0.95f, 0.9f, 44, true, 30L
            )
        )
        assertTrue(abs(cleanAfter.gains[0] - 2f) > abs(mixedAfter.gains[0] - 2f))
        assertTrue(abs(cleanAfter.gains[3] - 1.5f) > abs(mixedAfter.gains[3] - 1.5f))
    }

}
