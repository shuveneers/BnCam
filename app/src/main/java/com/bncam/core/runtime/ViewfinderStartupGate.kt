package com.bncam.core.runtime

/**
 * Process-local gate that keeps optional Vulkan warmup behind the first proven viewfinder
 * presentation. It has no camera, GL or Vulkan ownership; it only publishes a one-shot event.
 */
object ViewfinderStartupGate {
    data class FirstPresentation(
        val source: String,
        val generation: Int,
        val sensorTimestampNs: Long,
        val presentationTimestampNs: Long,
        val signal: String
    )

    private val lock = Any()
    private var firstPresentation: FirstPresentation? = null
    private val pendingActions = ArrayList<() -> Unit>(2)

    fun runAfterFirstPresentation(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (firstPresentation != null) {
                true
            } else {
                pendingActions += action
                false
            }
        }
        if (runNow) action()
    }

    fun signalFirstPresentation(
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        presentationTimestampNs: Long,
        signal: String
    ): Boolean {
        val actions: List<() -> Unit>
        synchronized(lock) {
            if (firstPresentation != null) return false
            firstPresentation = FirstPresentation(
                source = source,
                generation = generation,
                sensorTimestampNs = sensorTimestampNs,
                presentationTimestampNs = presentationTimestampNs,
                signal = signal
            )
            actions = pendingActions.toList()
            pendingActions.clear()
        }
        actions.forEach { it() }
        return true
    }

    fun snapshot(): FirstPresentation? = synchronized(lock) { firstPresentation }
}
