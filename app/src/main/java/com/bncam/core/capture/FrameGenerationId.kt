package com.bncam.core.capture

import java.util.concurrent.atomic.AtomicInteger

object FrameGenerationId {
    private val generation = AtomicInteger(1)
    @Volatile
    private var lastIncrementTimeMs = 0L

    fun get(): Int = generation.get()

    fun increment(): Int {
        lastIncrementTimeMs = System.currentTimeMillis()
        return generation.incrementAndGet()
    }

    fun reset() {
        generation.set(1)
        lastIncrementTimeMs = System.currentTimeMillis()
    }

    fun msSinceLastIncrement(): Long {
        return System.currentTimeMillis() - lastIncrementTimeMs
    }

    // Backdoor for testing cold start mode in unit tests
    fun forceColdStart() {
        lastIncrementTimeMs = System.currentTimeMillis()
    }

    // Backdoor for testing warm state mode in unit tests
    fun forceWarmState() {
        lastIncrementTimeMs = System.currentTimeMillis() - 10000L
    }
}
