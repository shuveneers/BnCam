package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase10TransientStateOwnershipRegressionTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/core/debug/ShotLogger.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `constructor does not clean another logger transient state`() {
        val source = File(appDir, "src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        val initBlock = source.substringAfter("    init {").substringBefore("    fun getShotDir")
        assertFalse("cleanupTerminalStateDirectories()" in initBlock)
        assertTrue("fun startNewShot" in source)
        assertTrue(
            source.substringAfter("fun startNewShot").substringBefore("private var heartbeatJob")
                .contains("cleanupTerminalStateDirectories()")
        )
        assertTrue(
            source.substringAfter("fun recoverStaleStartedAttempts").substringBefore("private fun cleanupTerminalStateDirectories")
                .contains("cleanupTerminalStateDirectories()")
        )
    }
}
