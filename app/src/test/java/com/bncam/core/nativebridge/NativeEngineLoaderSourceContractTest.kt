package com.bncam.core.nativebridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEngineLoaderSourceContractTest {
    @Test
    fun onlyAuthoritativeLoaderCallsSystemLoadLibrary() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java/com/bncam/core/nativebridge/NativeEngineLoader.kt").isFile }
            ?: error("Cannot locate app module")
        val sources = File(appDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val callers = sources.filter { it.readText().contains("System.loadLibrary") }
        assertEquals(listOf("NativeEngineLoader.kt"), callers.map { it.name })

        val loader = callers.single().readText()
        assertTrue(loader.contains("@Synchronized"))
        assertTrue(loader.contains("if (attempted)"))

        val imageUtils = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        assertTrue(imageUtils.contains("NativeEngineLoader.isAvailable"))
        assertFalse(imageUtils.contains("private fun loadNativeLibraries"))
    }
}
