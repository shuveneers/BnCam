package com.bncam.core.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class HdrEnhancedSupportRejectionTelemetrySourceContractTest {
    private fun source(relative: String): String = File(relative).readText()

    @Test
    fun nativeStatsExposeCompactSupportRejectionBreakdown() {
        val header = source("src/main/cpp/DngMerger.h")
        val cpp = source("src/main/cpp/DngMerger.cpp")
        listOf(
            "supportRejectedCanonicalization",
            "supportRejectedAlignment",
            "supportRejectedForwardBackward",
            "supportRejectedSpectraConsensus",
            "supportRejectedZeroWeightedContribution",
            "supportRejectedOther",
        ).forEach { key ->
            assertContains(header, key)
            assertContains(cpp, ";$key=")
        }
    }

    @Test
    fun hdrRunnerEmitsOneCompactBreakdownLineAndMetrics() {
        val runner = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertContains(runner, "HDR_ENHANCED_SUPPORT_REJECTIONS:")
        assertContains(runner, "hdrEnhancedSupportRejectedForwardBackward")
        assertContains(runner, "hdrEnhancedSupportRejectedSpectraConsensus")
        assertContains(runner, "hdrEnhancedSupportRejectedZeroWeightedContribution")
    }
}
