package com.bncam.core.vulkan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VulkanJniContractTest {
    @Test
    fun kotlinAndNativeRuntimeBridgeStayInLockstep() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/vulkan/VulkanNativeBridge.kt").isFile }
            ?: error("Cannot locate app module")
        val kotlin = File(appDir, "src/main/java/com/bncam/core/vulkan/VulkanNativeBridge.kt").readText()
        val native = File(appDir, "src/main/cpp/vulkan/VulkanRuntimeJni.cpp").readText()

        val kotlinNames = Regex("external\\s+fun\\s+(native\\w+)")
            .findAll(kotlin).map { it.groupValues[1] }.toSet()
        val nativeNames = Regex("Java_com_bncam_core_vulkan_VulkanNativeBridge_(native\\w+)")
            .findAll(native).map { it.groupValues[1] }.toSet()

        assertEquals(kotlinNames, nativeNames)
        assertFalse(kotlin.contains("Long // Vk"))
        assertFalse(kotlin.contains("import android.hardware.HardwareBuffer"))
        assertFalse(kotlinNames.any { it.contains("Handle", ignoreCase = true) })
    }
}
