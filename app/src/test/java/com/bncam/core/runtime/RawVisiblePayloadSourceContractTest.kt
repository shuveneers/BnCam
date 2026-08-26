package com.bncam.core.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVisiblePayloadSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/runtime/RawVisiblePayloadResolver.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appRoot(), relative).readText()

    @Test
    fun `first real raw image resolves visible payload before consumers use runtime geometry`() {
        val factory = source("src/main/java/com/bncam/core/runtime/RawPipelineRuntimeProfileFactory.kt")
        val resolver = source("src/main/java/com/bncam/core/runtime/RawVisiblePayloadResolver.kt")

        assertTrue(factory.contains("RawColumnStatsAuditor.auditImagePlaneStageA"))
        assertTrue(factory.contains("RawVisiblePayloadResolver.resolve"))
        assertTrue(factory.contains("image.cropRect"))
        assertTrue(factory.contains("withVisibleRawRect(payloadDecision.visibleRect)"))
        assertTrue(resolver.contains("Source.IMAGE_CROP_RECT"))
        assertTrue(resolver.contains("Source.STAGE_A_EDGE_PADDING"))
        assertFalse(resolver.contains("384 to"))
        assertFalse(resolver.contains("cropLeft = 384"))
    }

    @Test
    fun `raw preview processing and dng master share the same runtime crop`() {
        val imageUtils = source("src/main/java/com/bncam/core/engine/ImageUtils.kt")
        val preview = source("src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt")

        assertTrue(imageUtils.contains("RawPipelineRuntimeOwner.getProfile()"))
        assertTrue(imageUtils.contains("val cropLeft = runtimeGeometry?.cropLeft"))
        assertTrue(imageUtils.contains("val cropWidth = runtimeGeometry?.cropWidth"))
        assertTrue(imageUtils.contains("val crop = intArrayOf(cropLeft, cropTop, cropWidth, cropHeight)"))
        assertTrue(preview.contains("sourceCropLeft = if (request.useRuntimeCrop) activeGeometry?.cropLeft"))
        assertTrue(preview.contains("sourceCropWidth = if (request.useRuntimeCrop) activeGeometry?.cropWidth"))

        val shotLogger = source("src/main/java/com/bncam/core/debug/ShotLogger.kt")
        assertTrue(shotLogger.contains("Runtime RAW visible crop"))
        assertTrue(shotLogger.contains("geometry.cropTop"))
        assertTrue(shotLogger.contains("geometry.cropHeight"))
    }

    @Test
    fun `geometry refinement keeps cfa phase and preview dimensions coherent with crop origin`() {
        val geometry = source("src/main/java/com/bncam/core/runtime/RawStreamGeometry.kt")

        assertTrue(geometry.contains("fun withVisibleRawRect"))
        assertTrue(geometry.contains("val phaseX = (old.cfaPhaseX xor (deltaX and 1)) and 1"))
        assertTrue(geometry.contains("val phaseY = (old.cfaPhaseY xor (deltaY and 1)) and 1"))
        assertTrue(geometry.contains("captureAspectRatio = safe.width().toFloat() / safe.height().toFloat()"))
        assertTrue(geometry.contains("PreviewRawGeometry.create"))
    }
    @Test
    fun `stage a payload audit resolves row padding as well as column padding`() {
        val auditor = source("src/main/java/com/bncam/core/isp/raw/RawColumnStatsAuditor.kt")
        val resolver = source("src/main/java/com/bncam/core/runtime/RawVisiblePayloadResolver.kt")

        assertTrue(auditor.contains("leadingNearBlackRows"))
        assertTrue(auditor.contains("trailingNearBlackRows"))
        assertTrue(auditor.contains("rowEdgePaddingConfidence"))
        assertTrue(auditor.contains("for (y in 0 until height)"))
        assertTrue(resolver.contains("resolveLeadingRows"))
        assertTrue(resolver.contains("resolveTrailingRows"))
        assertTrue(resolver.contains("acceptedLeadingRows"))
        assertTrue(resolver.contains("acceptedTrailingRows"))
        assertFalse(resolver.contains("768"))
        assertFalse(resolver.contains("2304"))
    }

    @Test
    fun `heuristic stage a crop cannot break bayer cell parity or classify ordinary dark rows as padding`() {
        val resolver = source("src/main/java/com/bncam/core/runtime/RawVisiblePayloadResolver.kt")

        assertTrue(resolver.contains("preservesBayerCellParity(existing, candidate)"))
        assertTrue(resolver.contains("stageA_rejected_bayer_cell_parity"))
        assertTrue(resolver.contains("(removedTop and 1) == 0"))
        assertTrue(resolver.contains("(removedBottom and 1) == 0"))
        assertTrue(resolver.contains("bandMean * 2.0 > rowReference"))
        assertTrue(resolver.contains("Authoritative HAL cropRect handling remains separate above"))
    }

}
