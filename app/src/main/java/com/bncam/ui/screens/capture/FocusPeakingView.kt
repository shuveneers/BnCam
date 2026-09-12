package com.bncam.ui.screens.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import com.bncam.core.debug.RawPreviewFirstActivationTrace
import com.bncam.core.debug.Phase0PerformanceTrace
import com.bncam.core.engine.ImageUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FocusPeakingView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        const val RAW_GL_TEXTURE_SLOTS = 3
        const val RAW_BOUNDARY_LOG_INTERVAL_MS = 2_000L
        const val PRESENTATION_QUERY_TIMEOUT_NS = 1_000_000_000L
        const val FRAME_RATE_CHANGE_THRESHOLD_HZ = 0.25f
        const val DISPLAY_RATE_MATCH_TOLERANCE = 0.005f
        const val MAX_DISPLAY_RATE_DIVISOR = 8
        const val GUIDANCE_FLOAT_EPSILON = 0.0005f

        @Volatile var activeInstance: FocusPeakingView? = null
        @Volatile var lastDrawnSource: String = "NONE"
        @Volatile var lastDrawnSensorTimestampNs: Long = 0L
        @Volatile var lastDrawnGeneration: Int = -1
        @Volatile var rawFramesDrawnCount: Long = 0L
        @Volatile var yuvFramesDrawnCount: Long = 0L
        @Volatile var firstRawDrawnSensorTimestampNs: Long = 0L
        @Volatile var lastSwitchElapsedMs: Long = 0L
        @Volatile var firstRawDrawnLatencyMs: Float = -1f

        fun getProvenanceSummary(): Map<String, Any?> = mapOf(
            "lastDrawnSource" to lastDrawnSource,
            "lastDrawnSensorTimestampNs" to lastDrawnSensorTimestampNs,
            "lastDrawnGeneration" to lastDrawnGeneration,
            "rawFramesDrawnCount" to rawFramesDrawnCount,
            "yuvFramesDrawnCount" to yuvFramesDrawnCount,
            "firstRawDrawnSensorTimestampNs" to firstRawDrawnSensorTimestampNs,
            "firstRawDrawnLatencyMs" to firstRawDrawnLatencyMs,
            "isRawActivelyDrawn" to lastDrawnSource.startsWith("RAW"),
            "displayedSource" to (activeInstance?.displayedSource?.name ?: "UNKNOWN"),
            "acceptingRawFrames" to (activeInstance?.acceptingRawFrames ?: false)
        )
    }

    var onSurfaceTextureCreated: ((SurfaceTexture) -> Unit)? = null
    var onRawUploadTiming: ((Float) -> Unit)? = null
    var onYuvFrameAvailable: ((Long) -> Unit)? = null
    var onTargetFramePresented: ((String, String, Int) -> Unit)? = null

    var isPeakingEnabled: Boolean = false
    var peakingColor: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f) // Standaard Rood
    @Volatile private var liveSaturationOffset: Float = 0f
    @Volatile private var liveContrastOffset: Float = 0f
    @Volatile private var liveWhiteBalanceCompensation: FloatArray = floatArrayOf(1f, 1f, 1f)
    @Volatile private var yuvOrientationCorrectionDegrees: Int = 0
    @Volatile private var rawDisplayZoom: Float = 1f

    @Volatile private var peakingFocusConfidence: Float = 0f
    @Volatile private var peakingConfidenceClass: Float = 0f
    @Volatile private var peakingAfScanning: Boolean = false
    @Volatile private var peakingLensMoving: Boolean = false
    @Volatile private var peakingSubjectRoiActive: Boolean = false
    @Volatile private var peakingTargetCenterX: Float = 0.5f
    @Volatile private var peakingTargetCenterY: Float = 0.5f
    @Volatile private var peakingTargetRadiusX: Float = 0.5f
    @Volatile private var peakingTargetRadiusY: Float = 0.5f

    fun setFocusPeakingGuidance(
        focusConfidence: Float,
        confidenceClass: Float,
        afScanning: Boolean,
        lensMoving: Boolean,
        subjectRoiActive: Boolean,
        targetCenterX: Float,
        targetCenterY: Float,
        targetRadiusX: Float,
        targetRadiusY: Float
    ) {
        val nextFocusConfidence = focusConfidence.coerceIn(0f, 1f)
        val nextConfidenceClass = confidenceClass.coerceIn(-1f, 1f)
        val nextTargetCenterX = targetCenterX.coerceIn(0f, 1f)
        val nextTargetCenterY = targetCenterY.coerceIn(0f, 1f)
        val nextTargetRadiusX = targetRadiusX.coerceIn(0.02f, 0.5f)
        val nextTargetRadiusY = targetRadiusY.coerceIn(0.02f, 0.5f)
        val changed =
            abs(peakingFocusConfidence - nextFocusConfidence) > GUIDANCE_FLOAT_EPSILON ||
                abs(peakingConfidenceClass - nextConfidenceClass) > GUIDANCE_FLOAT_EPSILON ||
                peakingAfScanning != afScanning ||
                peakingLensMoving != lensMoving ||
                peakingSubjectRoiActive != subjectRoiActive ||
                abs(peakingTargetCenterX - nextTargetCenterX) > GUIDANCE_FLOAT_EPSILON ||
                abs(peakingTargetCenterY - nextTargetCenterY) > GUIDANCE_FLOAT_EPSILON ||
                abs(peakingTargetRadiusX - nextTargetRadiusX) > GUIDANCE_FLOAT_EPSILON ||
                abs(peakingTargetRadiusY - nextTargetRadiusY) > GUIDANCE_FLOAT_EPSILON

        peakingFocusConfidence = nextFocusConfidence
        peakingConfidenceClass = nextConfidenceClass
        peakingAfScanning = afScanning
        peakingLensMoving = lensMoving
        peakingSubjectRoiActive = subjectRoiActive
        peakingTargetCenterX = nextTargetCenterX
        peakingTargetCenterY = nextTargetCenterY
        peakingTargetRadiusX = nextTargetRadiusX
        peakingTargetRadiusY = nextTargetRadiusY
        if (changed) requestRender()
    }

    fun setYuvOrientationCorrection(degrees: Int) {
        val corrected = normalizeRightAngle(degrees)
        if (yuvOrientationCorrectionDegrees == corrected) return
        yuvOrientationCorrectionDegrees = corrected
        requestRender()
    }

    fun setRawDisplayZoom(zoom: Float) {
        val nextZoom = zoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        if (abs(rawDisplayZoom - nextZoom) <= GUIDANCE_FLOAT_EPSILON) return
        rawDisplayZoom = nextZoom
        requestRender()
    }

    fun setLiveCreativeTuning(
        saturationOffset: Float,
        contrastOffset: Float
    ) {
        // Preserve the independently-owned WB compensation while a creative side slider is
        // moving. This path is called directly from the pointer callback so YUV rendering does
        // not wait for a Compose/AndroidView update cycle.
        setLiveColorTuning(
            saturationOffset = saturationOffset,
            contrastOffset = contrastOffset,
            whiteBalanceCompensation = liveWhiteBalanceCompensation
        )
    }

    fun setLiveColorTuning(
        saturationOffset: Float,
        contrastOffset: Float,
        whiteBalanceCompensation: FloatArray = floatArrayOf(1f, 1f, 1f)
    ) {
        val nextSaturation = saturationOffset.coerceIn(-1f, 1f)
        val nextContrast = contrastOffset.coerceIn(-1f, 1f)
        val nextWhiteBalance = FloatArray(3) { index ->
            whiteBalanceCompensation.getOrElse(index) { 1f }
                .takeIf { it.isFinite() }
                ?.coerceIn(0.5f, 2f) ?: 1f
        }
        val changed =
            abs(liveSaturationOffset - nextSaturation) > GUIDANCE_FLOAT_EPSILON ||
                abs(liveContrastOffset - nextContrast) > GUIDANCE_FLOAT_EPSILON ||
                (0..2).any { index ->
                    abs(liveWhiteBalanceCompensation[index] - nextWhiteBalance[index]) > GUIDANCE_FLOAT_EPSILON
                }
        liveSaturationOffset = nextSaturation
        liveContrastOffset = nextContrast
        liveWhiteBalanceCompensation = nextWhiteBalance
        if (changed) requestRender()
    }

    fun captureSnapshot(targetWidth: Int = 128, targetHeight: Int = 128, callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && width > 0 && height > 0) {
            try {
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                PixelCopy.request(this, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        callback(bitmap)
                    } else {
                        bitmap.recycle()
                        callback(null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w("FocusPeakingView", "PixelCopy snapshot failed: ${e.message}")
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    private var surfaceTexture: SurfaceTexture? = null
    private var oesTextureId: Int = 0
    private var rawTextureId: Int = 0
    private val rawTextureIds = IntArray(RAW_GL_TEXTURE_SLOTS)
    private val rawTextureSlotWidths = IntArray(RAW_GL_TEXTURE_SLOTS)
    private val rawTextureSlotHeights = IntArray(RAW_GL_TEXTURE_SLOTS)
    private var rawTextureUploadCursor: Int = 0
    private var oesProgram: Int = 0
    private var rawProgram: Int = 0

    private data class ProgramHandles(
        val position: Int,
        val texCoord: Int,
        val sampler: Int,
        val stMatrix: Int,
        val peaking: Int,
        val color: Int,
        val liveSaturation: Int,
        val liveContrast: Int,
        val liveWhiteBalance: Int,
        val resolution: Int,
        val viewportResolution: Int,
        val focusConfidence: Int,
        val focusClass: Int,
        val afScanning: Int,
        val lensMoving: Int,
        val subjectRoi: Int,
        val targetCenter: Int,
        val targetRadius: Int
    )

    private var oesProgramHandles: ProgramHandles? = null
    private var rawProgramHandles: ProgramHandles? = null
    private val neutralWhiteBalance = floatArrayOf(1f, 1f, 1f)

    private var rawTextureWidth: Int = 1
    private var rawTextureHeight: Int = 1
    private var rawTextureRotationDegrees: Int = 0
    @Volatile private var rawTextureGeneration: Int = -1
    private val pendingRawFrame = AtomicReference<RawPreviewFrame?>(null)
    // GL-thread-owned display pin. A GPU-resident AHardwareBuffer must remain unavailable to
    // Vulkan for as long as it is the texture currently eligible for redraw. Releasing it after
    // only the first draw allows a later guidance/UI redraw to sample the same AHB while Vulkan
    // has already started overwriting it for a newer RAW frame.
    @Volatile private var pinnedDisplayedGpuFrame: RawPreviewFrame? = null

    @Volatile private var displayedSource: ViewfinderEffectiveSource = ViewfinderEffectiveSource.YUV
    @Volatile private var displayedGeneration: Int = -1
    @Volatile private var mirrorRawPreview: Boolean = false
    @Volatile private var displayReady: Boolean = false
    @Volatile private var oesFrameAvailable: Boolean = false
    @Volatile private var detached: Boolean = false
    @Volatile private var acceptingRawFrames: Boolean = false
    private var lastRawUploadLogMs: Long = 0L
    private var lastRawAcceptedLogMs: Long = 0L
    private var lastRawDrawnLogMs: Long = 0L
    private var lastDrawnRawFrame: RawPreviewFrame? = null
    private data class Phase0PendingDisplayCommit(
        val lensId: String,
        val source: String,
        val generation: Int
    )
    @Volatile private var phase0PendingDisplayCommit: Phase0PendingDisplayCommit? = null
    @Volatile private var phase0DispatchingYuvFrame: Boolean = false
    private data class PendingPresentation(
        val sensorTimestampNs: Long,
        val eglFrameId: Long,
        val queuedElapsedNs: Long,
        val lensId: String,
        val source: String,
        val generation: Int,
        val targetAuthorityAccepted: Boolean
    )
    private val pendingPresentations = ArrayDeque<PendingPresentation>()
    private val presentationLock = Any()
    private val rawFrameRateEstimator = RawSourceFrameRateEstimator()
    @Volatile private var appliedRawSurfaceFrameRate = 0f

    @Volatile
    private var diagnosticLensId: String = "unknown"

    @Volatile
    private var diagnosticBufferWidth: Int = 0

    @Volatile
    private var diagnosticBufferHeight: Int = 0

    @Volatile
    private var diagnosticViewportWidth: Int = 0

    @Volatile
    private var diagnosticViewportHeight: Int = 0

    @Volatile
    private var firstFrameDiagnosticsLogged: Boolean = false

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    // Focus peaking is a display-only GPU analysis pass. The fast path uses the already-fetched
    // center pixel plus four axial neighbours (five texture reads total per peaking fragment).
    // Curvature-to-gradient evidence rejects broad blurred edges, display-scale sampling avoids
    // sensor-pixel noise, and a real AF/subject ROI raises selectivity away from the focus target.
    private val externalFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform float uPeaking;
        uniform vec3 uColor;
        uniform vec2 uRes;
        uniform vec2 uViewportRes;
        uniform float uLiveSaturation;
        uniform float uLiveContrast;
        uniform vec3 uLiveWhiteBalance;
        uniform float uFocusConfidence;
        uniform float uFocusClass;
        uniform float uAfScanning;
        uniform float uLensMoving;
        uniform float uSubjectRoi;
        uniform vec2 uTargetCenter;
        uniform vec2 uTargetRadius;

        float applyContrast(float value, float signedControl) {
            float v = clamp(value, 0.0, 1.0);
            float factor = exp2(clamp(signedControl, -1.0, 1.0) * 0.75);
            if (abs(factor - 1.0) < 0.0001) return v;
            if (v < 0.5) return 0.5 * pow(max(0.0, 2.0 * v), factor);
            return 1.0 - 0.5 * pow(max(0.0, 2.0 * (1.0 - v)), factor);
        }

        vec3 srgbToLinear(vec3 c) {
            vec3 low = c / 12.92;
            vec3 high = pow((c + vec3(0.055)) / 1.055, vec3(2.4));
            return mix(low, high, step(vec3(0.04045), c));
        }

        vec3 linearToSrgb(vec3 c) {
            c = max(c, vec3(0.0));
            vec3 low = c * 12.92;
            vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - vec3(0.055);
            return mix(low, high, step(vec3(0.0031308), c));
        }

        vec3 applyLiveWhiteBalance(vec3 rgb) {
            // Camera OES/YUV arrives display encoded. Sensor-domain relative WB gains must be
            // applied in linear light; multiplying encoded channels directly exaggerates R/B
            // shifts and can create the full-frame magenta/purple cast seen during live WB.
            vec3 linearRgb = srgbToLinear(clamp(rgb, 0.0, 1.0));
            linearRgb *= uLiveWhiteBalance;
            return clamp(linearToSrgb(linearRgb), 0.0, 1.0);
        }

        vec3 applyLiveColor(vec3 rgb) {
            rgb = applyLiveWhiteBalance(rgb);
            float y = max(0.0, dot(rgb, vec3(0.2126, 0.7152, 0.0722)));
            if (abs(uLiveContrast) >= 0.0001) {
                float mappedY = applyContrast(y, uLiveContrast);
                float scale = y > 0.000001 ? mappedY / y : 1.0;
                rgb *= scale;
                y = mappedY;
            }
            if (abs(uLiveSaturation) >= 0.0001) {
                float saturationFactor = exp2(uLiveSaturation * 0.85);
                rgb = vec3(y) + (rgb - vec3(y)) * saturationFactor;
            }
            return clamp(rgb, 0.0, 1.0);
        }

        float rgbLuma(vec3 rgb) {
            return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        }

        float sampleLuma(vec2 uv) {
            return rgbLuma(texture2D(sTexture, clamp(uv, vec2(0.0), vec2(1.0))).rgb);
        }

        void main() {
            vec4 sourceColor = texture2D(sTexture, vTexCoord);
            float c = rgbLuma(sourceColor.rgb);
            vec4 color = sourceColor;
            color.rgb = applyLiveColor(color.rgb);
            if (uPeaking < 0.5) {
                gl_FragColor = color;
                return;
            }

            // Analyse approximately at displayed-pixel scale instead of adjacent sensor pixels.
            // This prevents high-resolution YUV/RAW sensor noise from masquerading as sharp detail.
            vec2 analysisRes = max(min(uRes, uViewportRes), vec2(1.0));
            vec2 texel = 1.0 / analysisRes;
            float n = sampleLuma(vTexCoord + vec2(0.0, -texel.y));
            float s = sampleLuma(vTexCoord + vec2(0.0,  texel.y));
            float w = sampleLuma(vTexCoord + vec2(-texel.x, 0.0));
            float e = sampleLuma(vTexCoord + vec2( texel.x, 0.0));

            float curvatureX = abs(e + w - 2.0 * c);
            float curvatureY = abs(n + s - 2.0 * c);
            float curvatureEvidence = max(curvatureX, curvatureY) +
                                      0.35 * min(curvatureX, curvatureY);
            float gradientEvidence = 0.5 * (abs(e - w) + abs(s - n));
            float localMin = min(c, min(min(n, s), min(w, e)));
            float localMax = max(c, max(max(n, s), max(w, e)));
            float localContrast = localMax - localMin;

            // Broad defocus edges can have a strong first derivative but little one-pixel
            // curvature. Resolved detail has materially more curvature relative to its gradient.
            float resolvedRatio = curvatureEvidence / (gradientEvidence + 0.010);
            float resolvedGate = smoothstep(0.34, 0.95, resolvedRatio);
            float contrastGate = smoothstep(0.025, 0.110, localContrast);
            float tonalGate = smoothstep(0.030, 0.090, c) *
                              (1.0 - smoothstep(0.93, 0.992, c));
            float focusLikelihood = curvatureEvidence *
                                    (0.15 + 0.85 * resolvedGate) *
                                    contrastGate * tonalGate;

            // Local image evidence owns peaking sensitivity. Global AF confidence may suppress
            // uncertain states, but it must never lower the local threshold and paint unrelated
            // background texture merely because Camera2 reports a confident focus state.
            float threshold = 0.062;
            if (uFocusClass < -0.5) threshold = 0.080;
            if (uAfScanning > 0.5) threshold *= 1.16;
            if (uLensMoving > 0.5) threshold *= 1.28;

            float roiAlpha = 1.0;
            if (uSubjectRoi > 0.5) {
                vec2 screenUv = vec2(
                    gl_FragCoord.x / max(uViewportRes.x, 1.0),
                    1.0 - gl_FragCoord.y / max(uViewportRes.y, 1.0)
                );
                vec2 delta = (screenUv - uTargetCenter) / max(uTargetRadius, vec2(0.02));
                float roiDistanceSquared = dot(delta, delta);
                float roiWeight = 1.0 - smoothstep(1.0, 4.0, roiDistanceSquared);
                threshold *= mix(1.55, 0.92, roiWeight);
                roiAlpha = mix(0.18, 1.0, roiWeight);
            }

            // While AF/lens motion is in flight the focus plane is not settled. Keep the overlay
            // stable rather than flashing marginal edges during the scan.
            float motionAlpha = 1.0;
            if (uAfScanning > 0.5) motionAlpha *= 0.70;
            if (uLensMoving > 0.5) motionAlpha *= 0.45;

            // Confidence is one-way authority: low/indeterminate confidence can reduce overlay
            // certainty, while high confidence cannot manufacture extra local sharpness evidence.
            float confidenceAlpha = 1.0;
            if (uFocusClass > 0.5) {
                confidenceAlpha = mix(0.82, 1.0, uFocusConfidence);
            } else if (uFocusClass < -0.5) {
                confidenceAlpha = 0.45;
            } else {
                confidenceAlpha = mix(0.58, 0.82, uFocusConfidence);
            }

            float peakAlpha = smoothstep(threshold, threshold * 2.10, focusLikelihood) *
                              0.92 * roiAlpha * motionAlpha * confidenceAlpha;
            gl_FragColor = vec4(mix(color.rgb, uColor, peakAlpha), color.a);
        }
    """.trimIndent()

    private val rawFragmentShaderCode = externalFragmentShaderCode
        .replace("#extension GL_OES_EGL_image_external : require\n", "")
        .replace("samplerExternalOES", "sampler2D")

    private val rawBlitFallbackFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,   1.0f, -1.0f,
        -1.0f,  1.0f,   1.0f,  1.0f
    )
    private val textureCoords = floatArrayOf(
        0.0f, 0.0f,     1.0f, 0.0f,
        0.0f, 1.0f,     1.0f, 1.0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexCoords).apply { position(0) }
    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoords).apply { position(0) }

    private val yuvStMatrix = FloatArray(16)
    private val rawStMatrix = FloatArray(16)

    init {
        activeInstance = this
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        Matrix.setIdentityM(yuvStMatrix, 0)
        Matrix.setIdentityM(rawStMatrix, 0)
    }

    fun setDisplayedSource(source: ViewfinderEffectiveSource, generation: Int) {
        displayedSource = source
        displayedGeneration = generation
        // RAW display ownership is validated again by submitRawPreviewFrame(). YUV authority is
        // accepted only when this callback is synchronously issued while handling an actual OES
        // frame; an eager callback-registration echo must not satisfy a lens-switch trace.
        if (source != ViewfinderEffectiveSource.YUV || phase0DispatchingYuvFrame) {
            phase0PendingDisplayCommit = Phase0PendingDisplayCommit(
                lensId = diagnosticLensId,
                source = source.name,
                generation = generation
            )
        }
        acceptingRawFrames = !detached && source != ViewfinderEffectiveSource.YUV
        pendingRawFrame.getAndSet(null)?.close()
        synchronized(presentationLock) { pendingPresentations.clear() }
        rawFrameRateEstimator.reset()
        lastSwitchElapsedMs = SystemClock.elapsedRealtime()
        if (source != ViewfinderEffectiveSource.YUV) {
            firstRawDrawnSensorTimestampNs = 0L
            firstRawDrawnLatencyMs = -1f
        }
        if (source == ViewfinderEffectiveSource.YUV) {
            clearRawSurfaceFrameRate()
            // Retire the last RAW display pin on the GL owner thread. The fence is ordered after
            // every prior RAW draw, so Vulkan cannot reclaim that AHB while GLES may still sample it.
            runCatching { queueEvent { retirePinnedDisplayedGpuFrameOnGlThread() } }
            // The validated OES frame is already resident because the YUV commit is issued from
            // onDrawFrame after updateTexImage(). Request the next draw to publish it.
            requestRender()
        }
        // RAW commits are immediately followed by submitRawPreviewFrame() for the exact validated
        // Vulkan frame. Let that submission request the first RAW draw so GL can never render a
        // newly committed RAW generation before its first texture payload is queued.
    }

    fun clearPreviewForCameraTransition() {
        acceptingRawFrames = false
        pendingRawFrame.getAndSet(null)?.close()
        runCatching { queueEvent { retirePinnedDisplayedGpuFrameOnGlThread() } }
        synchronized(presentationLock) { pendingPresentations.clear() }
        rawFrameRateEstimator.reset()
        clearRawSurfaceFrameRate()
        requestRender()
    }

    fun submitRawPreviewFrame(frame: RawPreviewFrame) {
        if (detached) {
            frame.close()
            return
        }
        if (displayedSource != ViewfinderEffectiveSource.YUV && frame.source != ViewfinderEffectiveSource.YUV) {
            if (frame.pipelineGeneration >= displayedGeneration) {
                displayedGeneration = frame.pipelineGeneration
                displayedSource = frame.source
                acceptingRawFrames = true
            }
        }
        if (!acceptingRawFrames ||
            displayedSource == ViewfinderEffectiveSource.YUV ||
            frame.source != displayedSource || frame.pipelineGeneration != displayedGeneration
        ) {
            frame.close()
            return
        }
        // A completed preview is intentionally only one frame deep. If GL has not consumed the
        // previous completion, replace it: the viewfinder must never build latency or back-pressure
        // the authoritative warm RAW/ZSL stream.
        pendingRawFrame.getAndSet(frame)?.close()
        Phase0PerformanceTrace.cameraFrameReceived(
            lensId = diagnosticLensId,
            source = frame.source.name,
            generation = frame.pipelineGeneration,
            sensorTimestampNs = frame.sensorTimestampNs
        )
        Phase0PerformanceTrace.targetSensorFrameReceived(
            lensId = diagnosticLensId,
            source = frame.source.name,
            generation = frame.pipelineGeneration,
            sensorTimestampNs = frame.sensorTimestampNs
        )
        RawPreviewCadenceDiagnostics.viewAccepted(
            frame.source,
            frame.pipelineGeneration,
            frame.sensorTimestampNs,
            rgbaHandoffBytes = if (frame.gpuResidentOutputUsed) 0L else
                frame.width.toLong() * frame.height.toLong() * 4L
        )
        RawPreviewFirstActivationTrace.viewAccepted(
            source = frame.source.name,
            generation = frame.pipelineGeneration,
            sensorTimestampNs = frame.sensorTimestampNs
        )
        rawFrameRateEstimator.offer(frame.sensorTimestampNs)?.let { sourceFps ->
            applyRawSurfaceFrameRate(sourceFps)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRawAcceptedLogMs >= RAW_BOUNDARY_LOG_INTERVAL_MS) {
            lastRawAcceptedLogMs = now
            Log.i(
                "BnCamRawPreview",
                "RAW_PREVIEW_VIEW_ACCEPTED source=${frame.source} " +
                    "generation=${frame.pipelineGeneration} rgbMin=${frame.outputRgbMin} " +
                    "rgbMax=${frame.outputRgbMax} rgbMean=${frame.outputRgbMean} alpha=${frame.outputAlpha}"
            )
        }
        // requestRender is thread-safe. Submitting as soon as Vulkan completes gives the GL thread
        // time to upload and swap before the next compositor latch; waiting for a UI Choreographer
        // callback here made otherwise-ready frames miss that latch and created visible 50 ms gaps.
        requestRender()
    }

    /**
     * Marks the Compose-owned display bridge dead before AndroidView release can race the GL
     * detach callback. Camera2 ownership is handled by BnCameraManager; this method only prevents
     * late renderer completions from touching a view whose Surface/EGL lifecycle is ending.
     */
    fun quiesceForComposeRelease() {
        detached = true
        acceptingRawFrames = false
        pendingRawFrame.getAndSet(null)?.close()
        synchronized(presentationLock) { pendingPresentations.clear() }
        rawFrameRateEstimator.reset()
        onSurfaceTextureCreated = null
        onRawUploadTiming = null
        onYuvFrameAvailable = null
        onTargetFramePresented = null
        surfaceTexture?.setOnFrameAvailableListener(null)
        displayReady = false
        oesFrameAvailable = false
    }

    fun setRawPreviewMirrored(mirrored: Boolean) {
        mirrorRawPreview = mirrored
    }

    fun setDiagnosticLensId(lensId: String) {
        if (diagnosticLensId != lensId) {
            diagnosticLensId = lensId
            firstFrameDiagnosticsLogged = false
        }
    }

    fun setDiagnosticPreviewBuffer(lensId: String, width: Int, height: Int) {
        diagnosticLensId = lensId
        diagnosticBufferWidth = width
        diagnosticBufferHeight = height
        firstFrameDiagnosticsLogged = false
        Log.i(
            "BnCamPreviewDiag",
            "event=GL_PREVIEW_BUFFER selectedLensId=$diagnosticLensId " +
                    "surfaceTextureDefaultBuffer=${width}x$height " +
                    "glViewport=${diagnosticViewportWidth}x$diagnosticViewportHeight " +
                    "scalingBehavior=raw_full_fov_aspect_fit"
        )
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A recreated EGL context cannot still sample the previous context's RAW texture. Any
        // retained display pin is therefore safe to return before new GL resources are created.
        pinnedDisplayedGpuFrame?.close()
        pinnedDisplayedGpuFrame = null
        detached = false
        acceptingRawFrames = displayedSource != ViewfinderEffectiveSource.YUV
        rawTextureGeneration = -1
        // Probe the actual GLES/EGL context plus the already initialized Vulkan runtime. The RAW
        // renderer enables AHardwareBuffer -> Vulkan -> EGLImage only when every required feature
        // (including non-blocking EGL fence sync for slot reuse) is present on this exact context.
        RawPreviewInteropCapabilities.probeOnGlThread(context)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        rawTextureSlotWidths.fill(0)
        rawTextureSlotHeights.fill(0)
        rawTextureUploadCursor = 0
        GLES20.glGenTextures(RAW_GL_TEXTURE_SLOTS, rawTextureIds, 0)
        rawTextureIds.forEach { textureId ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        rawTextureId = rawTextureIds[0]

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val externalFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, externalFragmentShaderCode)
        val rawFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, rawFragmentShaderCode)

        oesProgram = linkProgram(vertexShader, externalFragmentShader)
        rawProgram = linkProgram(vertexShader, rawFragmentShader)
        if (!isProgramLinked(rawProgram)) {
            Log.e("FocusPeakingView", "RAW focus-peaking shader link failed; using texture blit fallback")
            rawProgram = linkProgram(
                vertexShader,
                loadShader(GLES20.GL_FRAGMENT_SHADER, rawBlitFallbackFragmentShaderCode)
            )
        }
        oesProgramHandles = cacheProgramHandles(oesProgram)
        rawProgramHandles = cacheProgramHandles(rawProgram)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener(this@FocusPeakingView)
        }

        // Stuur de texture terug naar de main thread zodat Jetpack Compose en de Camera API hem kunnen gebruiken
        Handler(Looper.getMainLooper()).post {
            if (!detached) surfaceTexture?.let { onSurfaceTextureCreated?.invoke(it) }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        diagnosticViewportWidth = width
        diagnosticViewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        Log.i(
            "BnCamPreviewDiag",
            "event=GL_VIEWPORT selectedLensId=$diagnosticLensId " +
                    "viewport=0,0,${width}x$height " +
                    "surfaceTextureDefaultBuffer=${diagnosticBufferWidth}x$diagnosticBufferHeight " +
                    "scalingBehavior=raw_full_fov_aspect_fit"
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        if (detached) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        collectPresentedRawFrames()
        val useRaw = displayedSource != ViewfinderEffectiveSource.YUV
        var phase0YuvFrameUpdatedThisDraw = false
        // Drain the OES producer even while the last-known-good RAW texture is still displayed.
        // This lets the transition owner validate the exact Camera2 sensor timestamp of the first
        // YUV frame from a replacement session before switching display ownership to YUV.
        if (oesFrameAvailable) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(yuvStMatrix)
                val surfaceTimestampNs = surfaceTexture?.timestamp ?: 0L
                oesFrameAvailable = false
                if (!useRaw) displayReady = true
                if (surfaceTimestampNs > 0L) {
                    phase0YuvFrameUpdatedThisDraw = true
                    Phase0PerformanceTrace.cameraFrameReceived(
                        lensId = diagnosticLensId,
                        source = "YUV",
                        generation = displayedGeneration,
                        sensorTimestampNs = surfaceTimestampNs
                    )
                    phase0DispatchingYuvFrame = true
                    try {
                        onYuvFrameAvailable?.invoke(surfaceTimestampNs)
                    } finally {
                        phase0DispatchingYuvFrame = false
                    }
                    phase0PendingDisplayCommit
                        ?.takeIf { it.source == ViewfinderEffectiveSource.YUV.name }
                        ?.let { accepted ->
                            Phase0PerformanceTrace.targetSensorFrameReceived(
                                lensId = accepted.lensId,
                                source = accepted.source,
                                generation = accepted.generation,
                                sensorTimestampNs = surfaceTimestampNs
                            )
                        }
                }
            } catch (e: Exception) {
                // Ignore transient update exceptions during surface teardown or stream switch.
            }
        }
        if (useRaw) {
            Matrix.setIdentityM(rawStMatrix, 0)
        }

        var uploadedRawFrame: RawPreviewFrame? = null
        var gpuFrameToRetireAfterDraw: RawPreviewFrame? = null
        if (useRaw) {
            pendingRawFrame.getAndSet(null)?.let { frame ->
                var releaseImmediately = true
                try {
                    if (frame.source == displayedSource && frame.pipelineGeneration == displayedGeneration) {
                        RawPreviewCadenceDiagnostics.glUploadStarted(
                            frame.source,
                            frame.pipelineGeneration,
                            frame.sensorTimestampNs
                        )
                        val rawHandoffStartedNs = SystemClock.elapsedRealtimeNanos()
                        val uploadSlot = rawTextureUploadCursor
                        val uploadTextureId = rawTextureIds[uploadSlot]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTextureId)
                        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit

                        val handoffSucceeded = if (frame.gpuResidentOutputUsed && frame.hardwareBuffer != null) {
                            // No RGBA host readback and no glTex(Sub)Image2D upload: the exact AHB that
                            // Vulkan wrote is exposed to this GL texture through EGLImage.
                            ImageUtils.bindRawPreviewHardwareBufferToCurrentTexture(frame.hardwareBuffer)
                        } else {
                            frame.rgba.position(0)
                            if (rawTextureSlotWidths[uploadSlot] == frame.width &&
                                rawTextureSlotHeights[uploadSlot] == frame.height
                            ) {
                                GLES20.glTexSubImage2D(
                                    GLES20.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.rgba
                                )
                            } else {
                                GLES20.glTexImage2D(
                                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.width, frame.height,
                                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.rgba
                                )
                            }
                            GLES20.glGetError() == GLES20.GL_NO_ERROR
                        }

                        val handoffError = GLES20.glGetError()
                        val rawHandoffMs =
                            (SystemClock.elapsedRealtimeNanos() - rawHandoffStartedNs) / 1_000_000.0f
                        onRawUploadTiming?.invoke(rawHandoffMs)
                        if (handoffSucceeded && handoffError == GLES20.GL_NO_ERROR) {
                            rawTextureSlotWidths[uploadSlot] = frame.width
                            rawTextureSlotHeights[uploadSlot] = frame.height
                            rawTextureId = uploadTextureId
                            rawTextureUploadCursor = (uploadSlot + 1) % RAW_GL_TEXTURE_SLOTS
                            rawTextureWidth = frame.width
                            rawTextureHeight = frame.height
                            rawTextureRotationDegrees = frame.rotationDegrees
                            rawTextureGeneration = frame.pipelineGeneration
                            displayReady = true
                            lastDrawnRawFrame = frame
                            uploadedRawFrame = frame
                            val previousPinnedGpuFrame = pinnedDisplayedGpuFrame
                            if (frame.gpuResidentOutputUsed) {
                                // Keep the newly displayed AHB pinned across redraws. It is retired
                                // only after a successor has successfully taken display ownership.
                                pinnedDisplayedGpuFrame = frame
                                if (previousPinnedGpuFrame != null && previousPinnedGpuFrame !== frame) {
                                    gpuFrameToRetireAfterDraw = previousPinnedGpuFrame
                                }
                                releaseImmediately = false
                            } else if (previousPinnedGpuFrame != null) {
                                // CPU upload owns an independent GL texture copy. Once this draw is
                                // submitted the former GPU-backed display can be retired safely.
                                pinnedDisplayedGpuFrame = null
                                gpuFrameToRetireAfterDraw = previousPinnedGpuFrame
                            }
                            RawPreviewCadenceDiagnostics.glUploadCompleted(
                                frame.source,
                                frame.pipelineGeneration,
                                frame.sensorTimestampNs
                            )
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastRawUploadLogMs >= 5_000L) {
                                lastRawUploadLogMs = now
                                Log.i(
                                    "BnCamRawPreview",
                                    "RAW_PREVIEW_GL_HANDOFF source=${frame.source} " +
                                        "generation=${frame.pipelineGeneration} " +
                                        "size=${frame.width}x${frame.height} rotation=${frame.rotationDegrees} " +
                                        "gpuResident=${frame.gpuResidentOutputUsed}"
                                )
                            }
                        } else {
                            if (frame.gpuResidentOutputUsed) {
                                frame.closeAfterGlInteropFailure()
                                releaseImmediately = false
                            }
                            Log.e(
                                "BnCamRawPreview",
                                "RAW_PREVIEW_GL_HANDOFF_FAILED error=0x${handoffError.toString(16)} " +
                                    "gpuResident=${frame.gpuResidentOutputUsed} " +
                                    "size=${frame.width}x${frame.height}"
                            )
                        }
                    }
                } finally {
                    if (releaseImmediately) frame.close()
                }
            }
        }

        if (!firstFrameDiagnosticsLogged) {
            firstFrameDiagnosticsLogged = true
            val transform = (if (useRaw) rawStMatrix else yuvStMatrix).joinToString(prefix = "[", postfix = "]") {
                String.format(Locale.US, "%.6f", it)
            }
            Log.i(
                "BnCamPreviewDiag",
                "event=FIRST_SURFACE_TEXTURE_FRAME selectedLensId=$diagnosticLensId " +
                        "surfaceTextureDefaultBuffer=${diagnosticBufferWidth}x$diagnosticBufferHeight " +
                        "glViewport=${diagnosticViewportWidth}x$diagnosticViewportHeight " +
                        "surfaceTextureTransform=$transform " +
                        "vertexQuad=full_NDC " +
                        "scalingBehavior=RAW_FULL_FOV_ASPECT_FIT_YUV_SURFACE_TEXTURE_TRANSFORM"
            )
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!displayReady) return
        val program = if (useRaw) rawProgram else oesProgram
        val handles = if (useRaw) rawProgramHandles else oesProgramHandles
        if (handles == null) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (useRaw) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTextureId)
        else GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(handles.sampler, 0)

        val posHandle = handles.position
        val displayVertices = if (useRaw) {
            RawPreviewAspectFitTransform.vertices(
                clockwiseRotationDegrees = rawTextureRotationDegrees,
                sourceWidth = rawTextureWidth,
                sourceHeight = rawTextureHeight,
                viewportWidth = width,
                viewportHeight = height
            )
        } else {
            vertexCoords
        }
        vertexBuffer.position(0)
        vertexBuffer.put(displayVertices).position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texHandle = handles.texCoord
        texBuffer.position(0)
        texBuffer.put(
            if (useRaw) {
                RawPreviewTextureTransform.coordinates(
                    clockwiseRotationDegrees = rawTextureRotationDegrees,
                    mirrored = mirrorRawPreview,
                    digitalZoom = rawDisplayZoom
                )
            } else {
                yuvTextureCoordinates(yuvOrientationCorrectionDegrees)
            }
        ).position(0)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        val activeMatrix = if (useRaw) rawStMatrix else yuvStMatrix
        GLES20.glUniformMatrix4fv(handles.stMatrix, 1, false, activeMatrix, 0)
        GLES20.glUniform1f(handles.peaking, if (isPeakingEnabled) 1.0f else 0.0f)
        GLES20.glUniform3fv(handles.color, 1, peakingColor, 0)
        // RAW preview already receives the frozen/effective profile + live color tuning from the
        // native preview ISP. Apply the display-only creative delta only to YUV to avoid doubling.
        GLES20.glUniform1f(handles.liveSaturation, if (useRaw) 0f else liveSaturationOffset)
        GLES20.glUniform1f(handles.liveContrast, if (useRaw) 0f else liveContrastOffset)
        val wb = if (useRaw) neutralWhiteBalance else liveWhiteBalanceCompensation
        GLES20.glUniform3fv(handles.liveWhiteBalance, 1, wb, 0)
        GLES20.glUniform2f(
            handles.resolution,
            if (useRaw) rawTextureWidth.toFloat() else diagnosticBufferWidth.coerceAtLeast(width).toFloat(),
            if (useRaw) rawTextureHeight.toFloat() else diagnosticBufferHeight.coerceAtLeast(height).toFloat()
        )
        GLES20.glUniform2f(
            handles.viewportResolution,
            width.coerceAtLeast(1).toFloat(),
            height.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform1f(handles.focusConfidence, peakingFocusConfidence)
        GLES20.glUniform1f(handles.focusClass, peakingConfidenceClass)
        GLES20.glUniform1f(handles.afScanning, if (peakingAfScanning) 1f else 0f)
        GLES20.glUniform1f(handles.lensMoving, if (peakingLensMoving) 1f else 0f)
        GLES20.glUniform1f(handles.subjectRoi, if (peakingSubjectRoiActive) 1f else 0f)
        GLES20.glUniform2f(handles.targetCenter, peakingTargetCenterX, peakingTargetCenterY)
        GLES20.glUniform2f(handles.targetRadius, peakingTargetRadiusX, peakingTargetRadiusY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (useRaw) {
            val drawnFrame = uploadedRawFrame ?: lastDrawnRawFrame
            lastDrawnSource = drawnFrame?.source?.name ?: displayedSource.name
            lastDrawnSensorTimestampNs = drawnFrame?.sensorTimestampNs ?: 0L
            lastDrawnGeneration = drawnFrame?.pipelineGeneration ?: displayedGeneration
            rawFramesDrawnCount++
            if (firstRawDrawnSensorTimestampNs == 0L && drawnFrame != null && drawnFrame.sensorTimestampNs > 0L) {
                firstRawDrawnSensorTimestampNs = drawnFrame.sensorTimestampNs
                if (lastSwitchElapsedMs > 0L) {
                    firstRawDrawnLatencyMs = (SystemClock.elapsedRealtime() - lastSwitchElapsedMs).toFloat()
                }
            }
        } else {
            lastDrawnSource = "YUV"
            lastDrawnSensorTimestampNs = surfaceTexture?.timestamp ?: 0L
            lastDrawnGeneration = displayedGeneration
            yuvFramesDrawnCount++
        }

        val committedLensId = diagnosticLensId
        val committedSource = lastDrawnSource
        val committedGeneration = lastDrawnGeneration
        val committedSensorTimestampNs = lastDrawnSensorTimestampNs
        val phase0PendingCommit = phase0PendingDisplayCommit
        val phase0TargetAuthorityAccepted = phase0PendingCommit != null &&
            phase0PendingCommit.lensId == committedLensId &&
            phase0PendingCommit.source == committedSource &&
            phase0PendingCommit.generation == committedGeneration
        val phase0NewFrameCommitted = if (useRaw) {
            uploadedRawFrame != null
        } else {
            phase0YuvFrameUpdatedThisDraw || phase0TargetAuthorityAccepted
        }
        if (phase0NewFrameCommitted) {
            Phase0PerformanceTrace.viewfinderFrameCommitted(
                lensId = committedLensId,
                source = committedSource,
                generation = committedGeneration,
                sensorTimestampNs = committedSensorTimestampNs,
                targetAuthorityAccepted = phase0TargetAuthorityAccepted
            )
            if (phase0TargetAuthorityAccepted) phase0PendingDisplayCommit = null
            if (!useRaw) {
                // GLSurfaceView does not expose a SurfaceFlinger present fence for OES/YUV. Report
                // the first UI-vsync after GL submit as an explicitly-labelled presentation proxy.
                postOnAnimation {
                    Phase0PerformanceTrace.viewfinderFramePresented(
                        lensId = committedLensId,
                        source = committedSource,
                        generation = committedGeneration,
                        sensorTimestampNs = committedSensorTimestampNs,
                        presentationTimestampNs = SystemClock.elapsedRealtimeNanos(),
                        presentationSignal = "NEXT_UI_VSYNC_AFTER_GL_SUBMIT_PROXY",
                        targetAuthorityAccepted = phase0TargetAuthorityAccepted
                    )
                    if (phase0TargetAuthorityAccepted) {
                        onTargetFramePresented?.invoke(
                            committedLensId,
                            committedSource,
                            committedGeneration
                        )
                    }
                }
            }
        }

        gpuFrameToRetireAfterDraw?.let { frame ->
            // Fence retirement occurs only after a successor texture has been drawn. This preserves
            // the currently displayed AHB across arbitrary redraws without introducing a GL wait.
            // If fence creation fails the backing slot is quarantined rather than reused unsafely.
            frame.closeAfterGlFence(ImageUtils.createRawPreviewGlFence())
        }

        uploadedRawFrame?.let { frame ->
            val eglFrameId = ImageUtils.getRawPreviewEglNextFrameId()
            RawPreviewCadenceDiagnostics.drawSubmitted(
                frame.source,
                frame.pipelineGeneration,
                frame.sensorTimestampNs,
                eglFrameId
            )
            RawPreviewFirstActivationTrace.drawSubmitted(
                source = frame.source.name,
                generation = frame.pipelineGeneration,
                sensorTimestampNs = frame.sensorTimestampNs
            )
            if (eglFrameId > 0L) {
                synchronized(presentationLock) {
                    pendingPresentations.addLast(
                        PendingPresentation(
                            sensorTimestampNs = frame.sensorTimestampNs,
                            eglFrameId = eglFrameId,
                            queuedElapsedNs = SystemClock.elapsedRealtimeNanos(),
                            lensId = diagnosticLensId,
                            source = frame.source.name,
                            generation = frame.pipelineGeneration,
                            targetAuthorityAccepted = phase0TargetAuthorityAccepted
                        )
                    )
                }
            }
        }

        texBuffer.position(0)
        texBuffer.put(textureCoords).position(0)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

        if (useRaw) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRawDrawnLogMs >= RAW_BOUNDARY_LOG_INTERVAL_MS) {
                lastRawDrawnLogMs = now
                val frame = lastDrawnRawFrame
                Log.i(
                    "BnCamRawPreview",
                    "RAW_PREVIEW_FRAME_DRAWN source=$displayedSource generation=$displayedGeneration " +
                        "texture=${rawTextureWidth}x$rawTextureHeight rotation=$rawTextureRotationDegrees " +
                        "rgbMin=${frame?.outputRgbMin ?: -1f} " +
                        "rgbMax=${frame?.outputRgbMax ?: -1f} rgbMean=${frame?.outputRgbMean ?: -1f} " +
                        "alpha=${frame?.outputAlpha ?: -1}"
                )
            }
        }
    }

    private fun yuvTextureCoordinates(clockwiseRotationDegrees: Int): FloatArray {
        val normalized = normalizeRightAngle(clockwiseRotationDegrees)
        return when (normalized) {
            90 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
            180 -> floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
            270 -> floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
            else -> textureCoords.copyOf()
        }
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            normalized < 315 -> 270
            else -> 0
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        if (detached) return
        oesFrameAvailable = true
        requestRender()
    }

    private fun applyRawSurfaceFrameRate(sourceFps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !sourceFps.isFinite() || sourceFps <= 0f ||
            abs(sourceFps - appliedRawSurfaceFrameRate) < FRAME_RATE_CHANGE_THRESHOLD_HZ
        ) return
        post {
            if (detached) return@post
            val outputSurface = holder.surface
            if (!outputSurface.isValid) return@post
            val compatibleFps = displayCompatibleFrameRate(sourceFps)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    outputSurface.setFrameRate(
                        compatibleFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    )
                } else {
                    outputSurface.setFrameRate(compatibleFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
                appliedRawSurfaceFrameRate = compatibleFps
                Log.i(
                    "BnCamRawCadence",
                    "RAW_PRESENTATION_SOURCE_RATE_HZ=$sourceFps surfaceRateHz=$compatibleFps"
                )
            }.onFailure { Log.w("BnCamRawCadence", "Could not set adaptive RAW surface rate", it) }
        }
    }

    private fun displayCompatibleFrameRate(sourceFps: Float): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return sourceFps
        val refreshRates = display?.supportedModes?.map { it.refreshRate }?.filter { it > 0f }.orEmpty()
        val candidate = refreshRates
            .flatMap { refreshRate -> (1..MAX_DISPLAY_RATE_DIVISOR).map { refreshRate / it } }
            .minByOrNull { abs(it - sourceFps) }
            ?: return sourceFps
        return if (abs(candidate - sourceFps) / sourceFps <= DISPLAY_RATE_MATCH_TOLERANCE) {
            candidate
        } else {
            sourceFps
        }
    }

    private fun retirePinnedDisplayedGpuFrameOnGlThread() {
        val frame = pinnedDisplayedGpuFrame ?: return
        pinnedDisplayedGpuFrame = null
        // queueEvent executes on the GL owner thread. A fence inserted here is ordered after every
        // previous draw that could have sampled this AHB, while remaining fully non-blocking.
        frame.closeAfterGlFence(ImageUtils.createRawPreviewGlFence())
    }

    private fun clearRawSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || appliedRawSurfaceFrameRate == 0f) return
        if (detached) {
            appliedRawSurfaceFrameRate = 0f
            return
        }
        post {
            if (detached) {
                appliedRawSurfaceFrameRate = 0f
                return@post
            }
            val outputSurface = holder.surface
            if (!outputSurface.isValid) return@post
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    outputSurface.setFrameRate(
                        0f,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    )
                } else {
                    outputSurface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
                appliedRawSurfaceFrameRate = 0f
            }
        }
    }

    private fun collectPresentedRawFrames() {
        synchronized(presentationLock) {
            if (pendingPresentations.isEmpty()) return
            val now = SystemClock.elapsedRealtimeNanos()
            val iterator = pendingPresentations.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                val presentTimeNs = ImageUtils.getRawPreviewEglDisplayPresentTime(pending.eglFrameId)
                if (presentTimeNs > 0L) {
                    RawPreviewCadenceDiagnostics.displayPresented(
                        pending.sensorTimestampNs,
                        pending.eglFrameId,
                        presentTimeNs
                    )
                    RawPreviewFirstActivationTrace.displayPresented(
                        sensorTimestampNs = pending.sensorTimestampNs,
                        displayPresentMonotonicNs = presentTimeNs
                    )
                    Phase0PerformanceTrace.viewfinderFramePresented(
                        lensId = pending.lensId,
                        source = pending.source,
                        generation = pending.generation,
                        sensorTimestampNs = pending.sensorTimestampNs,
                        presentationTimestampNs = presentTimeNs,
                        presentationSignal = "EGL_DISPLAY_PRESENT_TIME",
                        targetAuthorityAccepted = pending.targetAuthorityAccepted
                    )
                    if (pending.targetAuthorityAccepted) {
                        post {
                            onTargetFramePresented?.invoke(
                                pending.lensId,
                                pending.source,
                                pending.generation
                            )
                        }
                    }
                    iterator.remove()
                } else if (now - pending.queuedElapsedNs > PRESENTATION_QUERY_TIMEOUT_NS) {
                    iterator.remove()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        // First reject producers and stop callbacks. Crucially, do NOT release SurfaceTexture
        // while GLSurfaceView's render thread may still be inside onDrawFrame(). The framework
        // super call synchronously retires that GL thread; only then is the texture bridge safe
        // to release from the UI thread.
        quiesceForComposeRelease()
        super.onDetachedFromWindow()
        // GLSurfaceView has synchronously retired its GL thread at this point, so no texture can
        // sample the pinned AHB anymore. RawPreviewFrame.close() is idempotent if a queued fence
        // retirement already ran before teardown.
        pinnedDisplayedGpuFrame?.close()
        pinnedDisplayedGpuFrame = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("FocusPeakingView", "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            }
        }
    }

    private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int =
        GLES20.glCreateProgram().also { linked ->
            GLES20.glAttachShader(linked, vertexShader)
            GLES20.glAttachShader(linked, fragmentShader)
            GLES20.glLinkProgram(linked)
        }

    private fun cacheProgramHandles(program: Int): ProgramHandles = ProgramHandles(
        position = GLES20.glGetAttribLocation(program, "aPosition"),
        texCoord = GLES20.glGetAttribLocation(program, "aTexCoord"),
        sampler = GLES20.glGetUniformLocation(program, "sTexture"),
        stMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix"),
        peaking = GLES20.glGetUniformLocation(program, "uPeaking"),
        color = GLES20.glGetUniformLocation(program, "uColor"),
        liveSaturation = GLES20.glGetUniformLocation(program, "uLiveSaturation"),
        liveContrast = GLES20.glGetUniformLocation(program, "uLiveContrast"),
        liveWhiteBalance = GLES20.glGetUniformLocation(program, "uLiveWhiteBalance"),
        resolution = GLES20.glGetUniformLocation(program, "uRes"),
        viewportResolution = GLES20.glGetUniformLocation(program, "uViewportRes"),
        focusConfidence = GLES20.glGetUniformLocation(program, "uFocusConfidence"),
        focusClass = GLES20.glGetUniformLocation(program, "uFocusClass"),
        afScanning = GLES20.glGetUniformLocation(program, "uAfScanning"),
        lensMoving = GLES20.glGetUniformLocation(program, "uLensMoving"),
        subjectRoi = GLES20.glGetUniformLocation(program, "uSubjectRoi"),
        targetCenter = GLES20.glGetUniformLocation(program, "uTargetCenter"),
        targetRadius = GLES20.glGetUniformLocation(program, "uTargetRadius")
    )

    private fun isProgramLinked(program: Int): Boolean {
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("FocusPeakingView", "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        }
        return status[0] != 0
    }
}
