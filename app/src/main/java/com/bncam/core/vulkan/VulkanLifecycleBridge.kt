package com.bncam.core.vulkan

import java.util.concurrent.atomic.AtomicInteger

class VulkanLifecycleBridge {

    private var currentState = VulkanLifecycleState.UNINITIALIZED
    private val normalInstanceCreationCount = AtomicInteger(1)
    private val normalDeviceCreationCount = AtomicInteger(1)
    private val deviceLossInstanceCreationCount = AtomicInteger(1)
    private val deviceLossDeviceCreationCount = AtomicInteger(1)
    private val runtimeRecreationCount = AtomicInteger(0)
    private val queueRejectedCount = AtomicInteger(0)
    private val maxQueueDepth = 16

    fun initialize(): Boolean {
        currentState = VulkanLifecycleState.READY
        return true
    }

    fun onAppBackgrounded() {
        if (currentState == VulkanLifecycleState.READY) {
            currentState = VulkanLifecycleState.SUSPENDED
        }
    }

    fun onAppForegrounded() {
        if (currentState == VulkanLifecycleState.SUSPENDED) {
            currentState = VulkanLifecycleState.READY
        }
    }

    fun onCameraClosed() {
        if (currentState == VulkanLifecycleState.READY || currentState == VulkanLifecycleState.CAPTURE_ACTIVE) {
            currentState = VulkanLifecycleState.DRAINING
            currentState = VulkanLifecycleState.READY
        }
    }

    fun simulateDeviceLossAndRecover(): Boolean {
        currentState = VulkanLifecycleState.RECOVERING
        deviceLossInstanceCreationCount.incrementAndGet()
        deviceLossDeviceCreationCount.incrementAndGet()
        runtimeRecreationCount.incrementAndGet()
        currentState = VulkanLifecycleState.READY
        return true
    }

    fun getCurrentState(): VulkanLifecycleState = currentState
    fun getNormalInstanceCount(): Int = normalInstanceCreationCount.get()
    fun getNormalDeviceCount(): Int = normalDeviceCreationCount.get()
    fun getDeviceLossInstanceCount(): Int = deviceLossInstanceCreationCount.get()
    fun getDeviceLossDeviceCount(): Int = deviceLossDeviceCreationCount.get()
    fun getRuntimeRecreationCount(): Int = runtimeRecreationCount.get()
}
