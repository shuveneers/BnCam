package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5FocusPeakingSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun peakingUsesDisplayScaleCurvatureEvidenceInsteadOfPlainEdges() {
        val view = File(appDir, "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
        assertTrue(view.contains("vec2 analysisRes = max(min(uRes, uViewportRes), vec2(1.0))"))
        assertTrue(view.contains("curvatureEvidence"))
        assertTrue(view.contains("gradientEvidence"))
        assertTrue(view.contains("resolvedRatio"))
        assertTrue(view.contains("resolvedGate"))
        assertTrue(view.contains("focusLikelihood"))
        assertFalse(view.contains("lapCoarse"))
        assertFalse(view.contains("float edge = sqrt(gx * gx + gy * gy)"))
        assertFalse(view.contains("if (edge > 0.6)"))
    }

    @Test
    fun peakingFastPathUsesFiveTextureReadsAndCachesGlLocations() {
        val view = File(appDir, "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
        val shader = view.substringAfter("private val externalFragmentShaderCode")
            .substringBefore("private val rawFragmentShaderCode")
        assertTrue(shader.contains("vec4 sourceColor = texture2D(sTexture, vTexCoord)"))
        assertTrue(shader.contains("float n = sampleLuma"))
        assertTrue(shader.contains("float s = sampleLuma"))
        assertTrue(shader.contains("float w = sampleLuma"))
        assertTrue(shader.contains("float e = sampleLuma"))
        assertFalse(shader.contains("float n2 ="))
        assertFalse(shader.contains("float s2 ="))
        assertFalse(shader.contains("float w2 ="))
        assertFalse(shader.contains("float e2 ="))

        val draw = view.substringAfter("override fun onDrawFrame")
            .substringBefore("private fun yuvTextureCoordinates")
        assertTrue(view.contains("private fun cacheProgramHandles"))
        assertTrue(draw.contains("val handles = if (useRaw) rawProgramHandles else oesProgramHandles"))
        assertFalse(draw.contains("glGetUniformLocation"))
        assertFalse(draw.contains("glGetAttribLocation"))
    }

    @Test
    fun peakingIsConditionedByRealFocusStateAndSubjectRoi() {
        val view = File(appDir, "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
        val camera = File(appDir, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").readText()
        val manager = File(appDir, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

        assertTrue(view.contains("uFocusConfidence"))
        assertTrue(view.contains("uFocusClass"))
        assertTrue(view.contains("uAfScanning"))
        assertTrue(view.contains("uLensMoving"))
        assertTrue(view.contains("uSubjectRoi"))
        assertTrue(view.contains("uTargetCenter"))
        assertTrue(view.contains("threshold *= mix(1.55, 0.92, roiWeight)"))
        assertTrue(view.contains("roiAlpha = mix(0.18, 1.0, roiWeight)"))
        assertTrue(camera.contains("focusPeakingDisplayTarget("))
        assertTrue(camera.contains("view.setFocusPeakingGuidance("))
        assertTrue(manager.contains("subjectRoiUsed = metrics.subjectRoiUsed"))
        assertTrue(manager.contains("afRegion = metrics.afRegion?.let(::Rect)"))
    }

    @Test
    fun peakingRemainsAZeroCpuPixelCopyGpuDisplayPass() {
        val view = File(appDir, "src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()
        val shader = view.substringAfter("private val externalFragmentShaderCode")
            .substringBefore("private val rawFragmentShaderCode")
        assertTrue(shader.contains("texture2D"))
        assertTrue(shader.contains("gl_FragColor"))
        assertFalse(shader.contains("Bitmap"))
        assertFalse(shader.contains("ByteBuffer"))
    }
}
