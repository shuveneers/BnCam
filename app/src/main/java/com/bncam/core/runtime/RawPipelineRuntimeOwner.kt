package com.bncam.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe owner of the current session's immutable profile and live state.
 */
object RawPipelineRuntimeOwner {
    private val currentProfile = AtomicReference<RawPipelineRuntimeProfile?>(null)
    private val _profileStateFlow = MutableStateFlow<RawPipelineRuntimeProfile?>(null)
    val profileFlow: StateFlow<RawPipelineRuntimeProfile?> = _profileStateFlow.asStateFlow()

    private val currentState = AtomicReference(RawPipelineRuntimeState())
    private val _stateFlow = MutableStateFlow(RawPipelineRuntimeState())
    val stateFlow: StateFlow<RawPipelineRuntimeState> = _stateFlow.asStateFlow()

    fun updateProfile(profile: RawPipelineRuntimeProfile?) {
        currentProfile.set(profile)
        _profileStateFlow.value = profile
    }

    fun getProfile(): RawPipelineRuntimeProfile? = currentProfile.get()

    fun updateState(transform: (RawPipelineRuntimeState) -> RawPipelineRuntimeState) {
        while (true) {
            val old = currentState.get()
            val updated = transform(old).copy(updatedAtElapsedNs = System.nanoTime())
            if (currentState.compareAndSet(old, updated)) {
                _stateFlow.value = updated
                break
            }
        }
    }

    fun getState(): RawPipelineRuntimeState = currentState.get()

    fun reset() {
        currentProfile.set(null)
        _profileStateFlow.value = null
        val initialState = RawPipelineRuntimeState()
        currentState.set(initialState)
        _stateFlow.value = initialState
    }
}
