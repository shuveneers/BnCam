package com.bncam.core.engine

import com.bncam.BuildConfig
import com.bncam.core.nativebridge.NativeEngineLoader
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.util.Log
import com.bncam.core.quality.NeutralYuvToneMapper
import android.hardware.camera2.CameraCharacteristics
import com.bncam.core.quality.ProfileCurveDefaults
import com.bncam.core.quality.ProfileColorTuning
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.quality.FocusConfidenceState
import com.bncam.core.quality.FinalSensorCalibration
import com.bncam.core.quality.YuvAwbMapper
import com.bncam.core.quality.PhysicalTemporalNoisePolicy
import com.bncam.core.quality.PhysicalNoiseSoContract
import com.bncam.data.settings.ResolvedLensHardwareSettings
import com.bncam.core.isp.raw.MasterRawFrame
import com.bncam.core.isp.raw.Raw16RenderInput
import com.bncam.core.isp.raw.RawInputSource
import com.bncam.core.isp.raw.RawMasterIntegrity
import com.bncam.core.isp.raw.LensShadingGrid
import com.bncam.core.isp.raw.NativeRaw16Buffer
import com.bncam.core.runtime.SensorToRawBufferTransform
import android.hardware.camera2.CaptureResult
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FrameCandidatePixelMetrics(
    val valid: Boolean,
    val sharpnessScore: Double,
    val meanNormalized: Double,
    val lowClippedFraction: Double,
    val highClippedFraction: Double,
    val textureScore: Double,
    val analysisTimeMs: Double,
    val source: String
) {
    val clippingScore: Double
        get() = (1.0 - (highClippedFraction * 8.0 + lowClippedFraction * 1.5)).coerceIn(0.0, 1.0)

    val exposureScore: Double
        get() {
            val midtoneFit = (1.0 - kotlin.math.abs(meanNormalized - 0.28) / 0.50).coerceIn(0.0, 1.0)
            return (midtoneFit * 0.65 + clippingScore * 0.35).coerceIn(0.0, 1.0)
        }
}

private fun packEdgeAntiZipper(edge: Float, antiZipper: Float): Float {
    val e = edge.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    val z = antiZipper.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    if (kotlin.math.abs(e) <= 1.0e-7f && z <= 1.0e-7f) return 0f
    val edgeCode = if (e >= 0f) 512 + kotlin.math.round(e * 511f).toInt()
                   else 512 - kotlin.math.round((-e) * 512f).toInt()
    val zipperCode = kotlin.math.round(z * 1023f).toInt().coerceIn(0, 1023)
    val packed = (edgeCode.coerceIn(0, 1023) and 1023) or ((zipperCode and 1023) shl 10)
    return -(packed + 1).toFloat() / 1048577f
}

object ImageUtils {

    data class UltraHdrGainmapArtifact(
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        val minContentBoost: Float,
        val maxContentBoost: Float,
        val gamma: Float,
        val offsetSdr: Float,
        val offsetHdr: Float,
        val pixels: ByteArray
    )

    data class RawJpegRenderResult(
        val jpegBytes: ByteArray,
        val ultraHdrGainmap: UltraHdrGainmapArtifact?
    )

    private const val TAG = "ImageUtils"

    fun retainRawPreviewHardwareBuffer(buffer: HardwareBuffer): Long {
        if (!nativeEngineAvailable) return 0L
        return try {
            retainRawPreviewHardwareBufferNative(buffer)
        } catch (t: Throwable) {
            Log.w(TAG, "RAW preview buffer retain failed", t)
            0L
        }
    }

    fun releaseRawPreviewHardwareBuffer(handle: Long) {
        if (handle == 0L || !nativeEngineAvailable) return
        try {
            releaseRawPreviewHardwareBufferNative(handle)
        } catch (t: Throwable) {
            Log.w(TAG, "RAW preview buffer release failed", t)
        }
    }

    fun getPreviewBufferTelemetry(): String {
        if (!nativeEngineAvailable) return "previewAcquireCount=0 previewReleaseCount=0 previewInFlightReferences=0 releaseAfterGpuCompletionCount=0 releaseBeforeGpuCompletionCount=0 maxPreviewBuffersInFlight=0"
        return try {
            getPreviewBufferTelemetryNative()
        } catch (_: Throwable) {
            "previewAcquireCount=0 previewReleaseCount=0 previewInFlightReferences=0 releaseAfterGpuCompletionCount=0 releaseBeforeGpuCompletionCount=0 maxPreviewBuffersInFlight=0"
        }
    }

    /** Returns the EGL frame id that GLSurfaceView will swap after the current onDrawFrame. */
    fun getRawPreviewEglNextFrameId(): Long {
        if (!nativeEngineAvailable) return 0L
        return try {
            getRawPreviewEglNextFrameIdNative()
        } catch (_: Throwable) {
            0L
        }
    }

    /** Returns SurfaceFlinger's actual display-present timestamp, or zero while unavailable. */
    fun getRawPreviewEglDisplayPresentTime(frameId: Long): Long {
        if (!nativeEngineAvailable || frameId <= 0L) return 0L
        return try {
            getRawPreviewEglDisplayPresentTimeNative(frameId)
        } catch (_: Throwable) {
            0L
        }
    }

    fun renderRawPreview(
        retainedHardwareBuffer: Long,
        sourceFormat: Int,
        cfaPattern: Int,
        requestedDemosaicMode: Int,
        blackLevels: FloatArray,
        whiteLevel: Int,
        wbGains: FloatArray,
        camera2PriorWbGains: FloatArray,
        colorMatrix: FloatArray,
        exposureGain: Float,
        captureSensitivityIso: Int,
        captureExposureTimeNs: Long,
        physicalGreenNoiseSo: FloatArray,
        focusDetailPriority: Float,
        profileToneExposure: Float,
        profileToneHighlights: Float,
        profileToneShadows: Float,
        profileToneWhites: Float,
        profileToneBlacks: Float,
        profileToneContrast: Float,
        profileLocalToneBias: Float,
        profileSaturation: Float,
        profileContrast: Float,
        profileVibrance: Float,
        profileDetailAmount: Float,
        profileDetailRadius: Float,
        profileDetailDetail: Float,
        profileDetailMasking: Float,
        profileNrLuminance: Float,
        profileNrLuminanceDetail: Float,
        profileNrLuminanceContrast: Float,
        profileNrColor: Float,
        profileNrColorDetail: Float,
        profileNrColorSmoothness: Float,
        toneCurve: FloatArray,
        gammaCurve: FloatArray,
        sectionCurve: FloatArray,
        rotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRowStrideBytes: Int,
        sourcePixelStrideBytes: Int,
        sourceCropLeft: Int,
        sourceCropTop: Int,
        sourceCropWidth: Int,
        sourceCropHeight: Int,
        outputHardwareBuffer: HardwareBuffer?,
        outputRgba: ByteBuffer,
        analysisNv21: ByteBuffer?,
        frameSlotIndex: Int,
        maxWidth: Int,
        maxHeight: Int,
        analysisReadbackRequested: Boolean = true,
        profilePop: Float = 0f,
        profileColorRecovery: Float = 0f
    ): IntArray? {
        if (!nativeEngineAvailable || retainedHardwareBuffer == 0L || !outputRgba.isDirect) {
            return null
        }
        val profileSaturationCarrier = if (
            profileSaturation.isFinite() && profileSaturation <= -1.5f
        ) {
            // Already encoded by an upstream caller; never clamp/re-encode a valid carrier.
            profileSaturation
        } else {
            ProfileColorTuning(
                vibrance = profileVibrance,
                saturation = profileSaturation,
                contrast = profileContrast,
                pop = profilePop,
                colorRecovery = profileColorRecovery
            ).nativeSaturationCarrier()
        }
        return try {
            renderRawPreviewNative(
                retainedHardwareBuffer,
                sourceFormat,
                cfaPattern,
                requestedDemosaicMode,
                blackLevels,
                whiteLevel,
                wbGains,
                camera2PriorWbGains,
                colorMatrix,
                exposureGain,
                captureSensitivityIso,
                captureExposureTimeNs,
                physicalGreenNoiseSo,
                focusDetailPriority,
                profileToneExposure,
                profileToneHighlights,
                profileToneShadows,
                profileToneWhites,
                profileToneBlacks,
                profileToneContrast,
                profileLocalToneBias,
                profileSaturationCarrier,
                profileContrast,
                profileVibrance,
                profileDetailAmount,
                profileDetailRadius,
                profileDetailDetail,
                profileDetailMasking,
                // N005: classical Profile NR is YUV-only. RAW keeps these legacy JNI slots
                // neutral until NeuralDenoiseControls replaces the transport contract.
                0.0f,
                0.5f,
                0.0f,
                0.0f,
                0.5f,
                0.5f,
                toneCurve,
                gammaCurve,
                sectionCurve,
                rotationDegrees,
                sourceWidth,
                sourceHeight,
                sourceRowStrideBytes,
                sourcePixelStrideBytes,
                sourceCropLeft,
                sourceCropTop,
                sourceCropWidth,
                sourceCropHeight,
                outputHardwareBuffer,
                outputRgba,
                analysisNv21,
                frameSlotIndex,
                maxWidth,
                maxHeight,
                analysisReadbackRequested
            )
        } catch (t: Throwable) {
            Log.w(TAG, "RAW preview render failed", t)
            null
        }
    }

    @Volatile
    private var rawJpegDebugDumpsEnabled: Boolean = false

    @Volatile
    private var rawJpegDebugDumpDirectory: String = ""

    /** Developer-only intermediate RAW JPEG dumps. Production default is disabled. */
    fun configureRawJpegDebugDumps(enabled: Boolean, directory: String? = null) {
        val requestedDirectory = directory.orEmpty()
        val directoryReady = if (enabled && requestedDirectory.isNotBlank()) {
            runCatching { java.io.File(requestedDirectory).apply { mkdirs() }.isDirectory }.getOrDefault(false)
        } else {
            false
        }
        rawJpegDebugDumpDirectory = if (directoryReady) requestedDirectory else ""
        rawJpegDebugDumpsEnabled = enabled && directoryReady
        Log.i(TAG, "RAW JPEG intermediate dumps enabled=$rawJpegDebugDumpsEnabled directory=${rawJpegDebugDumpDirectory.ifBlank { "none" }}")
    }

    private val nativeLoadFailure: Throwable?
        get() = NativeEngineLoader.failureOrNull()

    val nativeEngineAvailable: Boolean
        get() = NativeEngineLoader.isAvailable

    fun nativeLoadStatus(): String = NativeEngineLoader.status()

    private fun List<Float>?.toNativeCurveArray(expectedSize: Int): FloatArray {
        val source = this ?: emptyList()
        return FloatArray(expectedSize) { index ->
            source.getOrNull(index)?.coerceIn(0.0f, 1.0f)
                ?: if (expectedSize <= 1) 0.0f else index.toFloat() / (expectedSize - 1).toFloat()
        }
    }

    /**
     * YUV frames already contain Camera HAL white balance. Convert the resolved live/lens AWB
     * from an absolute RAW gain target into a bounded post-HAL RGB compensation.
     * System/Auto resolves to identity because effective and base gains are equal.
     */
    private fun resolveYuvLiveAwbCompensation(
        calibration: FinalSensorCalibration?
    ): FloatArray = YuvAwbMapper.resolve(
        baseCameraGains = calibration?.base?.baseWbGains,
        targetGains = calibration?.effectiveWbGains
    )

    private data class NativeLensShadingMap(
        val gains: FloatArray,
        val columns: Int,
        val rows: Int,
        val fromMetadata: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NativeLensShadingMap

            if (!gains.contentEquals(other.gains)) return false
            if (columns != other.columns) return false
            if (rows != other.rows) return false
            if (fromMetadata != other.fromMetadata) return false

            return true
        }

        override fun hashCode(): Int {
            var result = gains.contentHashCode()
            result = 31 * result + columns
            result = 31 * result + rows
            result = 31 * result + fromMetadata.hashCode()
            return result
        }
    }

    private fun CaptureResult?.toNativeLensShadingMap(): NativeLensShadingMap {
        val map = try {
            this?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        } catch (_: Throwable) {
            null
        }
        if (map == null) {
            return NativeLensShadingMap(
                gains = FloatArray(0),
                columns = 0,
                rows = 0,
                fromMetadata = false
            )
        }

        return try {
            val columns = map.columnCount.coerceAtLeast(0)
            val rows = map.rowCount.coerceAtLeast(0)
            if (columns <= 0 || rows <= 0) {
                NativeLensShadingMap(FloatArray(0), 0, 0, false)
            } else {
                val gains = FloatArray(columns * rows * 4)
                var index = 0
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        gains[index++] = map.getGainFactor(0, column, row)
                        gains[index++] = map.getGainFactor(1, column, row)
                        gains[index++] = map.getGainFactor(2, column, row)
                        gains[index++] = map.getGainFactor(3, column, row)
                    }
                }
                val sanitized = LensShadingGrid.sanitize(gains, columns, rows)
                if (sanitized == null) {
                    NativeLensShadingMap(FloatArray(0), 0, 0, false)
                } else {
                    NativeLensShadingMap(sanitized, columns, rows, true)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LensShadingMap extraction failed; native RAW ISP will use identity LSC", t)
            NativeLensShadingMap(FloatArray(0), 0, 0, false)
        }
    }

    // =====================================================================================
    // SAFE NATIVE WRAPPERS
    // =====================================================================================

    fun analyzeFrameCandidateSafe(
        buffer: HardwareBuffer,
        sourceFormat: Int,
        nativeWhiteLevel: Int,
        nativeBlackLevels: IntArray
    ): FrameCandidatePixelMetrics {
        if (!nativeEngineAvailable) {
            return FrameCandidatePixelMetrics(false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "native_unavailable")
        }
        return try {
            val values = analyzeFrameCandidateNative(
                buffer,
                sourceFormat,
                nativeWhiteLevel.coerceIn(1, 65535),
                IntArray(4) { nativeBlackLevels.getOrElse(it) { 0 } }
            )
            if (values == null || values.size < 7 || values[0] < 0.5f) {
                FrameCandidatePixelMetrics(false, 0.0, 0.0, 0.0, 0.0, 0.0, values?.getOrNull(6)?.toDouble() ?: 0.0, "native_lock_or_format_failed")
            } else {
                FrameCandidatePixelMetrics(
                    valid = true,
                    sharpnessScore = values[1].toDouble().coerceIn(0.0, 1.0),
                    meanNormalized = values[2].toDouble().coerceIn(0.0, 1.0),
                    lowClippedFraction = values[3].toDouble().coerceIn(0.0, 1.0),
                    highClippedFraction = values[4].toDouble().coerceIn(0.0, 1.0),
                    textureScore = values[5].toDouble().coerceIn(0.0, 1.0),
                    analysisTimeMs = values[6].toDouble().coerceAtLeast(0.0),
                    source = "native_sampled_pixels"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame candidate pixel analysis failed", t)
            FrameCandidatePixelMetrics(false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "native_exception")
        }
    }

    fun processNativeYuvSafe(
        buffers: Array<HardwareBuffer>,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int,
        lensId: String,
        captureSensitivityIso: Int = 0,
        exposureScaleToAnchor: FloatArray = FloatArray(buffers.size) { 1f },
        computationalHdr: Boolean = false,
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext? = null
    ): ByteArray? {
        val post = qualityConfig?.post
        val yuvAwbCompensation = resolveYuvLiveAwbCompensation(qualityConfig?.finalCalibration)
        val routeLabel = if (buffers.size > 1) "YUV_COMPUTE" else "YUV_FAST"
        val portrait = portraitCaptureContext?.takeIf { it.available }
        val portraitMaskArtifact = portrait?.mask
        val portraitBounds = portrait?.targetBoundsNormalized
        val portraitMaskBuffer = portraitMaskArtifact?.duplicateMask()
        Log.d(TAG, "YUV tone contract: ${NeutralYuvToneMapper.toneModeDescription()}")
        return invokeNativeSafely(routeLabel) {
            processNativeYuv(
                buffers = buffers,
                lensId = lensId,
                jpegQuality = post?.jpegQuality ?: 98,
                captureSensitivityIso = captureSensitivityIso,
                rotationDegrees = rotationDegrees,
                profileYuvWbRed = yuvAwbCompensation[0],
                profileYuvWbGreen = yuvAwbCompensation[1],
                profileYuvWbBlue = yuvAwbCompensation[2],
                profileColorSaturation = qualityConfig?.profileColorTuning?.nativeSaturationCarrier() ?: 0.0f,
                profileColorContrast = qualityConfig?.profileColorTuning?.contrast ?: 0.0f,
                profilePresenceVibrance = qualityConfig?.profileColorTuning?.vibrance ?: 0.0f,
                profileDetailAmount = qualityConfig?.profileDetailTuning?.amount ?: com.bncam.data.settings.ProfileDetailDefaults.AMOUNT,
                // Phase 4 transport compatibility: the retired Radius JNI slot carries signed Legibility.
                profileDetailRadius = qualityConfig?.profileDetailTuning?.legibility ?: com.bncam.data.settings.ProfilePlannedDefaults.LEGIBILITY,
                profileDetailDetail = qualityConfig?.profileDetailTuning?.detail ?: com.bncam.data.settings.ProfileDetailDefaults.DETAIL,
                // Phase 2 transport compatibility: native profileDetailMasking is an ABI slot only.
                // It carries standalone Edge authority; the Sharp Mask setting remains disconnected.
                profileDetailMasking = qualityConfig?.profileDetailTuning?.let { packEdgeAntiZipper(it.edge, it.antiZipper) } ?: 0f,
                // Phase 6: retired Profile-NR controls no longer own YUV pixels. Keep the
                // legacy JNI tuple at its exact neutral values so it cannot become a second owner.
                profileNrLuminance = 0.0f,
                profileNrLuminanceDetail = 0.5f,
                profileNrLuminanceContrast = 0.0f,
                profileNrColor = 0.0f,
                profileNrColorDetail = 0.5f,
                profileNrColorSmoothness = 0.5f,
                toneCurve = qualityConfig?.curves?.toneNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_TONE).toFloatArray(),
                gammaCurve = qualityConfig?.curves?.gammaNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_GAMMA).toFloatArray(),
                sectionCurve = qualityConfig?.curves?.sectionNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_SECT).toFloatArray(),
                exposureScaleToAnchor = exposureScaleToAnchor,
                computationalHdr = computationalHdr,
                ultraHdrGainmapEnabled = qualityConfig?.ultraHdrGainmapEnabled == true,
                portraitEffectEnabled = portrait != null,
                portraitMask = portraitMaskBuffer,
                portraitMaskWidth = portraitMaskArtifact?.maskWidth ?: 0,
                portraitMaskHeight = portraitMaskArtifact?.maskHeight ?: 0,
                portraitTargetLeft = portraitBounds?.left ?: 0f,
                portraitTargetTop = portraitBounds?.top ?: 0f,
                portraitTargetRight = portraitBounds?.right ?: 0f,
                portraitTargetBottom = portraitBounds?.bottom ?: 0f,
                portraitMaskRotationDegrees = portraitMaskArtifact?.rotationDegrees ?: 0
            )
        }
    }

    /**
     * Computational-HDR YUV render with optional GPU-generated Ultra HDR gainmap.
     * Single-frame YUV intentionally yields no gainmap because an 8-bit camera YUV frame does
     * not contain recoverable HDR highlight authority. All gainmap pixel math stays in Vulkan.
     */
    fun processNativeYuvWithUltraHdrSafe(
        buffers: Array<HardwareBuffer>,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int,
        lensId: String,
        captureSensitivityIso: Int = 0,
        exposureScaleToAnchor: FloatArray = FloatArray(buffers.size) { 1f },
        computationalHdr: Boolean = false,
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext? = null
    ): RawJpegRenderResult? {
        val jpeg = processNativeYuvSafe(
            buffers = buffers,
            qualityConfig = qualityConfig,
            rotationDegrees = rotationDegrees,
            lensId = lensId,
            captureSensitivityIso = captureSensitivityIso,
            exposureScaleToAnchor = exposureScaleToAnchor,
            computationalHdr = computationalHdr,
            portraitCaptureContext = portraitCaptureContext
        ) ?: run {
            if (qualityConfig?.ultraHdrGainmapEnabled == true && computationalHdr) {
                try { consumeLastUltraHdrGainmapArtifactNative() } catch (_: Throwable) { null }
            }
            return null
        }
        val gainmap = if (qualityConfig?.ultraHdrGainmapEnabled == true && computationalHdr) {
            try { decodeUltraHdrGainmapArtifact(consumeLastUltraHdrGainmapArtifactNative()) }
            catch (failure: Throwable) {
                Log.w(TAG, "YUV Ultra HDR gainmap artifact consume failed", failure)
                null
            }
        } else null
        return RawJpegRenderResult(jpegBytes = jpeg, ultraHdrGainmap = gainmap)
    }

    fun renderJpegFromRaw16InputSafe(
        masterFrame: Raw16RenderInput,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int = masterFrame.orientationDegrees,
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext? = null
    ): ByteArray? {
        val post = qualityConfig?.post
        // The Master RAW owns the capture-specific post-observer/post-fusion calibration.
        // Profile/UI config remains authoritative for settings, but must not overwrite the
        // actual noise state measured while constructing this RAW16 input.
        val finalCal = masterFrame.finalCalibration ?: qualityConfig?.finalCalibration
        val wb = qualityConfig?.whiteBalanceGains
        val colorMatrix = qualityConfig?.colorCorrectionMatrix
        val captureResult = masterFrame.captureResult
        val rggbVector = captureResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val fallbackWb = if (rggbVector != null) {
            floatArrayOf(rggbVector.red, rggbVector.greenEven, rggbVector.greenOdd, rggbVector.blue)
        } else {
            floatArrayOf(1f, 1f, 1f, 1f)
        }
        // SensorCalibrationResolver is authoritative. In System mode its output is the exact
        // frame's Camera2 gains; in profile modes it is the one resolved profile target.
        // Never re-prefer CaptureResult here or the profile selection would be silently lost.
        val nativeWb = finalCal?.effectiveWbGains?.takeIf { it.size >= 4 }
            ?: wb?.toNativeArray()
            ?: fallbackWb
        val nativeWbFromMetadata = finalCal?.effectiveWbSource
            ?.contains("CaptureResult", ignoreCase = true)
            ?: (rggbVector != null)
        val nativeColorMatrix = finalCal?.effectiveColorMatrix ?: colorMatrix?.toNativeArray() ?: floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        // JPEG-only levels come from RawFrameInfo's explicit Master RAW16 unit contract.
        // DNG continues to use masterFrame.blackLevels/whiteLevel and an explicit native-buffer materialization.
        val domainInfo = masterFrame.rawFrameInfo
        val nativeBlackLevels = FloatArray(4) { index ->
            domainInfo.developedRawBlackLevelsCanonicalInMasterUnits.getOrElse(index) { 0f }
        }
        val nativeWhiteLevel = domainInfo.effectiveWhiteLevelInMasterUnits.roundToInt().coerceAtLeast(1)
        val captureSensitivityIso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val captureExposureTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val demosaicAfHints = masterFrame.demosaicAfHints
        val predictiveKnown = demosaicAfHints.predictiveConfidence >= 0.30f
        val afFocused = demosaicAfHints.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            demosaicAfHints.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        val afScanning = demosaicAfHints.afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
            demosaicAfHints.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
        val lensStationary = demosaicAfHints.lensState == CaptureResult.LENS_STATE_STATIONARY
        val velocityMagnitude = kotlin.math.abs(demosaicAfHints.focusVelocityDioptersPerSec)
        val velocityStability = if (predictiveKnown) {
            (1f - velocityMagnitude / 0.40f).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val focusClassStability = when (demosaicAfHints.confidenceState) {
            FocusConfidenceState.CONFIDENT_SHARP -> 1.0f
            FocusConfidenceState.CONFIDENT_SOFT -> 0.20f
            FocusConfidenceState.INDETERMINATE -> 0.45f
        }
        val demosaicFocusStabilityKnown = demosaicAfHints.focusConfidence > 0.01f ||
            predictiveKnown || afFocused || afScanning
        val demosaicFocusStabilityConfidence = if (demosaicFocusStabilityKnown) {
            (
                0.35f * demosaicAfHints.focusConfidence.coerceIn(0f, 1f) +
                    0.20f * focusClassStability +
                    0.15f * (if (afFocused) 1f else if (afScanning) 0f else 0.5f) +
                    0.10f * (if (lensStationary) 1f else 0f) +
                    0.20f * velocityStability
                ).coerceIn(0f, 1f)
        } else 0f
        val demosaicFocusSharpConfidence = when (demosaicAfHints.confidenceState) {
            FocusConfidenceState.CONFIDENT_SHARP -> demosaicAfHints.focusConfidence.coerceIn(0f, 1f)
            FocusConfidenceState.CONFIDENT_SOFT -> 0f
            FocusConfidenceState.INDETERMINATE -> 0.25f * demosaicAfHints.focusConfidence.coerceIn(0f, 1f)
        }
        val demosaicFocusMotionRisk = when {
            predictiveKnown -> (velocityMagnitude / 0.40f).coerceIn(0f, 1f)
            afScanning || !lensStationary -> 0.70f
            afFocused -> 0.05f
            else -> 0.20f
        }
        val faceDetectMode = try {
            captureResult?.get(CaptureResult.STATISTICS_FACE_DETECT_MODE) ?: 0
        } catch (_: Throwable) {
            0
        }
        val detectedFaces = try {
            captureResult?.get(CaptureResult.STATISTICS_FACES)?.filter { it.score >= 50 }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val activeArray = masterFrame.characteristics
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeArrayArea = activeArray?.let { it.width().toLong() * it.height().toLong() }
            ?.takeIf { it > 0L } ?: 0L
        val maxFaceCoverage = if (activeArrayArea > 0L) {
            detectedFaces.maxOfOrNull { face ->
                val faceArea = face.bounds.width().coerceAtLeast(0).toLong() *
                    face.bounds.height().coerceAtLeast(0).toLong()
                (faceArea.toDouble() / activeArrayArea.toDouble()).toFloat()
            }?.coerceIn(0f, 1f) ?: 0f
        } else 0f
        val maxFaceScore = detectedFaces.maxOfOrNull { it.score }
            ?.div(100f)?.coerceIn(0f, 1f) ?: 0f
        val faceScaleConfidence = (maxFaceCoverage / 0.12f).coerceIn(0f, 1f)
        val demosaicPersonConfidence = if (detectedFaces.isNotEmpty()) {
            (maxFaceScore * (0.55f + 0.45f * faceScaleConfidence)).coerceIn(0f, 1f)
        } else 0f
        val demosaicPersonEvidenceKnown = faceDetectMode != 0 || detectedFaces.isNotEmpty()
        val capturePostRawSensitivityBoost = try {
            captureResult?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        } catch (_: Throwable) {
            100
        }
        // Freeze the actual shutter-time values used by this render invocation. The resolver
        // owns the calibrated arrays; the capture result owns ISO/exposure/post-RAW gain.
        val spectraSnapshot = finalCal?.noiseSnapshot?.copy(
            sourceFormat = masterFrame.source.name,
            iso = captureSensitivityIso,
            exposureTimeNs = captureExposureTimeNs,
            postRawSensitivityBoost = capturePostRawSensitivityBoost,
            cfaPattern = masterFrame.cfaPattern,
            whiteLevel = nativeWhiteLevel,
            blackLevel = nativeBlackLevels
        )
        // FASE 11: one physical-noise JNI carrier only. The payload is exactly eight doubles
        // in canonical R/Gr/Gb/B order: S_R,O_R,S_Gr,O_Gr,S_Gb,O_Gb,S_B,O_B.
        // No presence/applied/count mirrors and no parallel SPECTRA effective-S/O arrays cross JNI.
        val physicalNoiseState = spectraSnapshot?.physicalNoiseState()
        val physicalNoiseSo = if (
            finalCal?.normalizationCalibrationValid == true &&
            finalCal.cfaSupportedForBayerNoiseModel
        ) {
            physicalNoiseState?.toInterleavedProfileOrNull() ?: DoubleArray(0)
        } else {
            DoubleArray(0)
        }

        val lensShadingMap = captureResult.toNativeLensShadingMap()
        val curves = qualityConfig?.curves
        val routeLabel = if (masterFrame.frameCount == 1) {
            "JPEG_WORKING_LINEAR_RAW_FROM_${masterFrame.source.name}_SINGLE"
        } else {
            "JPEG_WORKING_LINEAR_RAW_FROM_${masterFrame.source.name}_MASTER"
        }
        val masterTimingStats = masterFrame.dngMergeStats.split(';').mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0 || separator >= field.lastIndex) null
            else field.substring(0, separator).trim() to
                (field.substring(separator + 1).trim().toFloatOrNull() ?: 0.0f)
        }.toMap()
        val demosaicTemporalAcceptedPairs =
            (masterTimingStats["spectraAcceptedFramePairs"] ?: 0f).roundToInt().coerceAtLeast(0)
        val demosaicTemporalStaticP50 =
            (masterTimingStats["spectraStaticProbabilityP50"] ?: 0f).coerceIn(0f, 1f)
        val demosaicTemporalMotionAcceptance =
            (masterTimingStats["spectraMotionConfidence"] ?: 0f).coerceIn(0f, 1f)
        val demosaicTemporalObserverConfidence =
            (masterTimingStats["spectraObserverConfidence"] ?: 0f).coerceIn(0f, 1f)
        val demosaicTemporalStabilityKnown = demosaicTemporalAcceptedPairs > 0 &&
            demosaicTemporalObserverConfidence > 0f
        val demosaicTemporalStaticConfidence = if (demosaicTemporalStabilityKnown) {
            (0.65f * demosaicTemporalStaticP50 +
                0.35f * demosaicTemporalMotionAcceptance).coerceIn(0f, 1f)
        } else 0f
        val portrait = portraitCaptureContext?.takeIf { it.available }
        val portraitMaskArtifact = portrait?.mask
        val portraitBounds = portrait?.targetBoundsNormalized
        val portraitMaskBuffer = portraitMaskArtifact?.duplicateMask()
        val profileDetailTuning = qualityConfig?.profileDetailTuning?.sanitized()
            ?: com.bncam.core.quality.ProfileDetailTuning()

        // Profile/UI owns only the SPECTRA request intent. The capture-local FinalSensorCalibration
        // may carry an effective downstream state after physical adaptation, so it must not be used
        // as the JNI request source. Native combines this immutable profile request with the frozen
        // PhysicalNoiseState S/O and disables Neural production when that physical model is invalid.
        val spectraRequestedByProfile = qualityConfig?.profileNoiseTuning?.spectraEnabled == true

        return masterFrame.nativeRaw16Buffer.withDirectBuffer { raw16DirectBuffer ->
            // Full-payload CRC verification scans the complete RAW16 array twice. Keep it available
            // for explicit developer dump/integrity sessions, but not on every debug shot.
            val masterFingerprint = if (BuildConfig.DEBUG && rawJpegDebugDumpsEnabled) {
                RawMasterIntegrity.fingerprint(raw16DirectBuffer, masterFrame.raw16ByteCount)
            } else {
                null
            }
            val jpegBytes = invokeNativeSafely(routeLabel) {
                renderJpegFromMasterNative(
                lensId = masterFrame.lensId,
                raw16DirectBuffer = raw16DirectBuffer,
                width = masterFrame.width,
                height = masterFrame.height,
                routeLabel = routeLabel,
                isRaw10 = masterFrame.source == RawInputSource.RAW10,
                rotationDegrees = rotationDegrees,
                cfaPattern = masterFrame.cfaPattern,
                requestedDemosaicMode = qualityConfig?.demosaic?.requestedMode?.bridgeValue
                    ?: com.bncam.core.quality.DemosaicMode.DEFAULT.bridgeValue,
                demosaicFallbackOccurred = qualityConfig?.demosaic?.fallbackOccurred == true,
                demosaicFallbackReason = qualityConfig?.demosaic?.fallbackReason ?: "none",
                demosaicFocusStabilityKnown = demosaicFocusStabilityKnown,
                demosaicFocusStabilityConfidence = demosaicFocusStabilityConfidence,
                demosaicFocusSharpConfidence = demosaicFocusSharpConfidence,
                demosaicFocusMotionRisk = demosaicFocusMotionRisk,
                demosaicFocusVelocityDioptersPerSec = demosaicAfHints.focusVelocityDioptersPerSec,
                demosaicPredictiveAfConfidence = demosaicAfHints.predictiveConfidence.coerceIn(0f, 1f),
                demosaicPersonEvidenceKnown = demosaicPersonEvidenceKnown,
                demosaicPersonConfidence = demosaicPersonConfidence,
                demosaicDetectedFaceCount = detectedFaces.size,
                demosaicMaxFaceCoverage = maxFaceCoverage,
                demosaicTemporalStabilityKnown = demosaicTemporalStabilityKnown,
                demosaicTemporalStaticConfidence = demosaicTemporalStaticConfidence,
                demosaicTemporalObserverConfidence = demosaicTemporalObserverConfidence,
                demosaicTemporalMotionAcceptance = demosaicTemporalMotionAcceptance,
                demosaicTemporalAcceptedPairs = demosaicTemporalAcceptedPairs,
                captureSensitivityIso = captureSensitivityIso,
                captureExposureTimeNs = captureExposureTimeNs,
                raw10UnpackMs = masterTimingStats["raw10UnpackMs"] ?: 0.0f,
                raw10ToMasterRaw16Ms = masterTimingStats["raw10ToMasterRaw16Ms"] ?: 0.0f,
                raw10MergeOrSingleMasterMs = masterTimingStats["raw10MergeOrSingleMasterMs"] ?: 0.0f,
                rawSensorReadMs = masterTimingStats["rawSensorReadMs"] ?: 0.0f,
                rawSensorToMasterRaw16Ms = masterTimingStats["rawSensorToMasterRaw16Ms"] ?: 0.0f,
                blackLevelArray = nativeBlackLevels,
                whiteLevel = nativeWhiteLevel,
                sourceRowStrideBytes = domainInfo.sourceRowStrideBytes,
                sourcePixelStrideBytes = domainInfo.sourcePixelStrideBytes,
                masterRowStrideBytes = domainInfo.masterRowStrideBytes,
                bufferOriginCfaOffsetX = domainInfo.bufferOriginCfaOffsetX,
                bufferOriginCfaOffsetY = domainInfo.bufferOriginCfaOffsetY,
                sensorInfoWhiteLevel = domainInfo.sensorInfoWhiteLevel ?: 0,
                sensorDynamicWhiteLevel = domainInfo.sensorDynamicWhiteLevel ?: 0,
                sensorBlackLevelPattern = domainInfo.sensorBlackLevelPattern,
                sensorDynamicBlackLevel = domainInfo.sensorDynamicBlackLevel,
                chosenBlackLevelSource = domainInfo.developedRawBlackLevelSource,
                chosenWhiteLevelSource = domainInfo.developedRawWhiteLevelSource,
                sourceBitDepth = domainInfo.sourceBitDepth,
                effectiveSourceRange = domainInfo.effectiveSourceRange,
                masterStorageScale = domainInfo.masterStorageScale,
                masterStorageLeftShift = domainInfo.masterStorageLeftShift,
                masterStorageContract = domainInfo.masterStorageContract,
                wbGains = nativeWb,
                wbFromMetadata = nativeWbFromMetadata,
                awbCalibrationAuthority = finalCal?.awbCalibrationAuthority ?: 0f,
                awbExplicitDevelopedAuthority = finalCal?.awbExplicitDevelopedAuthority == true,
                awbManualGreenSplitAuthority = finalCal?.awbExplicitDevelopedAuthority == true &&
                    finalCal.awbRequestedGreenSplitMode.equals("Manual", ignoreCase = true),
                colorMatrix = nativeColorMatrix,
                colorMatrixFromMetadata = finalCal?.effectiveColorMatrixSource?.let { source ->
                    source.contains("CaptureResult", ignoreCase = true) || source.contains("CameraCharacteristics", ignoreCase = true)
                } ?: (colorMatrix?.fromMetadata ?: false),
                spectraProcessingEnabled = spectraRequestedByProfile,
                physicalNoiseSo = physicalNoiseSo,
                spectraPostRawSensitivityBoost = capturePostRawSensitivityBoost,
                // Neural master authority and Adaptive Response are fixed at 100% when the
                // spectraProcessingEnabled gate is true. Only component/protection controls cross JNI.
                profileSpectraLuma = qualityConfig?.profileNoiseTuning?.spectraLuma ?: 0.0f,
                profileSpectraChroma = qualityConfig?.profileNoiseTuning?.spectraChroma ?: 0.0f,
                profileSpectraDetailProtection = qualityConfig?.profileNoiseTuning?.spectraDetailProtection ?: 0.0f,
                profileSpectraLowFrequency = qualityConfig?.profileNoiseTuning?.spectraLowFrequency ?: 0.0f,
                profileToneExposure = qualityConfig?.profileToneTuning?.exposure ?: 0.0f,
                profileToneHighlights = qualityConfig?.profileToneTuning?.highlights ?: 0.0f,
                profileToneShadows = qualityConfig?.profileToneTuning?.shadows ?: 0.0f,
                profileToneWhites = qualityConfig?.profileToneTuning?.whites ?: 0.0f,
                profileToneBlacks = qualityConfig?.profileToneTuning?.blacks ?: 0.0f,
                profileToneContrast = qualityConfig?.profileToneTuning?.contrast ?: 0.0f,
                profileLocalToneBias = qualityConfig?.profileToneTuning?.localToneBias ?: 0.0f,
                profileColorSaturation = qualityConfig?.profileColorTuning?.nativeSaturationCarrier() ?: 0.0f,
                profileColorContrast = qualityConfig?.profileColorTuning?.contrast ?: 0.0f,
                profilePresenceVibrance = qualityConfig?.profileColorTuning?.vibrance ?: 0.0f,
                profileDetailAmount = profileDetailTuning.amount,
                // Phase 4 transport compatibility: the retired Radius JNI slot carries signed Legibility.
                profileDetailRadius = profileDetailTuning.legibility,
                profileDetailDetail = profileDetailTuning.detail,
                // Phase 2 transport compatibility: this legacy JNI slot carries Edge only.
                profileDetailMasking = packEdgeAntiZipper(profileDetailTuning.edge, profileDetailTuning.antiZipper),
                knownHotPixelMap = masterFrame.knownHotPixelMap.packedXy,
                lensShadingMap = lensShadingMap.gains,
                lensShadingColumns = lensShadingMap.columns,
                lensShadingRows = lensShadingMap.rows,
                lensShadingFromMetadata = lensShadingMap.fromMetadata,
                ultraHdrGainmapEnabled = qualityConfig?.ultraHdrGainmapEnabled == true,
                jpegQuality = post?.jpegQuality ?: 98,
                toneCurve = curves?.toneNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_TONE).toFloatArray(),
                gammaCurve = curves?.gammaNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_GAMMA).toFloatArray(),
                sectionCurve = curves?.sectionNodes?.toFloatArray()
                    ?: ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_SECT).toFloatArray(),
                activeArray = masterFrame.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                    intArrayOf(it.left, it.top, it.right, it.bottom)
                },
                cropRegion = masterFrame.captureResult?.get(CaptureResult.SCALER_CROP_REGION)?.let {
                    intArrayOf(it.left, it.top, it.right, it.bottom)
                },
                preCorrectionArray = masterFrame.characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let {
                    intArrayOf(it.left, it.top, it.right, it.bottom)
                },
                rawDebugDumpsEnabled = rawJpegDebugDumpsEnabled,
                rawDebugDumpDirectory = rawJpegDebugDumpDirectory,
                phoneAssistanceSensorsEnabled = masterFrame.phoneAssistanceSensorsEnabled,
                auxSensorValid = masterFrame.colorSensorReading.isValid,
                auxCctKelvin = masterFrame.colorSensorReading.cctKelvin,
                auxContributionWeight = masterFrame.colorSensorContributionWeight,
                portraitEffectEnabled = portrait != null,
                portraitMask = portraitMaskBuffer,
                portraitMaskWidth = portraitMaskArtifact?.maskWidth ?: 0,
                portraitMaskHeight = portraitMaskArtifact?.maskHeight ?: 0,
                portraitTargetLeft = portraitBounds?.left ?: 0f,
                portraitTargetTop = portraitBounds?.top ?: 0f,
                portraitTargetRight = portraitBounds?.right ?: 0f,
                portraitTargetBottom = portraitBounds?.bottom ?: 0f,
                portraitMaskRotationDegrees = portraitMaskArtifact?.rotationDegrees ?: 0
            )
        }
        if (masterFingerprint != null) {
            if (!RawMasterIntegrity.isUnchanged(
                    raw16DirectBuffer,
                    masterFrame.raw16ByteCount,
                    masterFingerprint
                )
            ) {
                Log.e(TAG, "FATAL RAW contract violation: JPEG processing mutated the native Master RAW16 payload")
                return@withDirectBuffer null
            }
            Log.d(TAG, "Native Master RAW16 integrity PASS after JPEG processing crc32=${masterFingerprint.crc32}")
        }
            jpegBytes
        }
    }

    fun renderJpegFromRaw16InputWithUltraHdrSafe(
        masterFrame: Raw16RenderInput,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int = masterFrame.orientationDegrees,
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext? = null
    ): RawJpegRenderResult? {
        val jpeg = renderJpegFromRaw16InputSafe(
            masterFrame = masterFrame,
            qualityConfig = qualityConfig,
            rotationDegrees = rotationDegrees,
            portraitCaptureContext = portraitCaptureContext
        ) ?: run {
            if (qualityConfig?.ultraHdrGainmapEnabled == true) {
                try { consumeLastUltraHdrGainmapArtifactNative() } catch (_: Throwable) { null }
            }
            return null
        }
        val gainmap = if (qualityConfig?.ultraHdrGainmapEnabled == true) {
            try { decodeUltraHdrGainmapArtifact(consumeLastUltraHdrGainmapArtifactNative()) }
            catch (failure: Throwable) {
                Log.w(TAG, "Ultra HDR gainmap artifact consume failed", failure)
                null
            }
        } else null
        return RawJpegRenderResult(jpegBytes = jpeg, ultraHdrGainmap = gainmap)
    }

    fun renderJpegFromMasterFrameWithUltraHdrSafe(
        masterFrame: MasterRawFrame,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int = masterFrame.orientationDegrees,
        portraitCaptureContext: com.bncam.core.capture.PortraitCaptureContext? = null
    ): RawJpegRenderResult? = renderJpegFromRaw16InputWithUltraHdrSafe(
        masterFrame = masterFrame,
        qualityConfig = qualityConfig,
        rotationDegrees = rotationDegrees,
        portraitCaptureContext = portraitCaptureContext
    )

    fun packageUltraHdrJpegSafe(
        baseJpeg: ByteArray,
        gainmap: UltraHdrGainmapArtifact
    ): ByteArray? {
        if (!nativeEngineAvailable || baseJpeg.isEmpty() || gainmap.pixels.isEmpty()) return null
        return try {
            packageUltraHdrJpegNative(
                baseJpeg = baseJpeg,
                gainmapPixels = gainmap.pixels,
                width = gainmap.width,
                height = gainmap.height,
                rowStrideBytes = gainmap.rowStrideBytes,
                minContentBoost = gainmap.minContentBoost,
                maxContentBoost = gainmap.maxContentBoost,
                gamma = gainmap.gamma,
                offsetSdr = gainmap.offsetSdr,
                offsetHdr = gainmap.offsetHdr
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "Ultra HDR JPEG packaging failed", failure)
            null
        }
    }

    private fun decodeUltraHdrGainmapArtifact(payload: ByteArray?): UltraHdrGainmapArtifact? {
        if (payload == null || payload.size < 40) return null
        val expectedMagic = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
            'H'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
        if (!payload.copyOfRange(0, 8).contentEquals(expectedMagic)) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        val width = buffer.int
        val height = buffer.int
        val rowStride = buffer.int
        val minBoost = buffer.float
        val maxBoost = buffer.float
        val gamma = buffer.float
        val offsetSdr = buffer.float
        val offsetHdr = buffer.float
        val bytesRequired = rowStride.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || rowStride < width || bytesRequired <= 0L ||
            bytesRequired > Int.MAX_VALUE || buffer.remaining() < bytesRequired.toInt() ||
            !maxBoost.isFinite() || maxBoost <= 1.0f) return null
        val pixels = ByteArray(bytesRequired.toInt())
        buffer.get(pixels)
        return UltraHdrGainmapArtifact(
            width = width, height = height, rowStrideBytes = rowStride,
            minContentBoost = minBoost, maxContentBoost = maxBoost, gamma = gamma,
            offsetSdr = offsetSdr, offsetHdr = offsetHdr, pixels = pixels
        )
    }

    fun renderJpegFromMasterFrameSafe(
        masterFrame: MasterRawFrame,
        qualityConfig: RenderQualityConfig?,
        rotationDegrees: Int = masterFrame.orientationDegrees
    ): ByteArray? = renderJpegFromRaw16InputSafe(
        masterFrame = masterFrame,
        qualityConfig = qualityConfig,
        rotationDegrees = rotationDegrees
    )

    private data class PhysicalTemporalNoisePayload(
        val temporalNoiseModelEnabled: Boolean,
        val effectiveS: DoubleArray,
        val effectiveO: DoubleArray,
        val confidence: Float,
        val authoritySource: String
    )

    private fun PhysicalTemporalNoisePayload.toInterleavedSoOrEmpty(): DoubleArray {
        if (!temporalNoiseModelEnabled) return DoubleArray(0)
        return PhysicalNoiseSoContract.pack(effectiveS, effectiveO)
    }

    private fun FinalSensorCalibration?.toPhysicalTemporalNoisePayload(): PhysicalTemporalNoisePayload {
        val snapshot = this?.noiseSnapshot
        val decision = PhysicalTemporalNoisePolicy.resolve(
            snapshotEffectiveS = snapshot?.effectiveS,
            snapshotEffectiveO = snapshot?.effectiveO
        )
        return PhysicalTemporalNoisePayload(
            temporalNoiseModelEnabled = decision.enabled,
            effectiveS = decision.effectiveS,
            effectiveO = decision.effectiveO,
            confidence = decision.confidence,
            authoritySource = decision.authoritySource
        )
    }

    fun mergeRaw10NativeRaw16Safe(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        characteristics: CameraCharacteristics,
        payloadWhiteLevel: Int,
        payloadBlackLevels: IntArray,
        maxFramesCap: Int = 16,
        maxShiftPixels: Int = 150,
        alignmentStrictness: Float = 0.8f,
        finalCalibration: FinalSensorCalibration? = null,
        fuseSupportFrames: Boolean = true,
        exposureScaleToAnchor: FloatArray = FloatArray(buffers.size) { 1f },
        computationalHdr: Boolean = false,
        developedWhiteLevel: Int = payloadWhiteLevel,
        developedBlackLevels: IntArray = payloadBlackLevels
    ): NativeRaw16Buffer? {
        val physicalTemporalNoise = finalCalibration.toPhysicalTemporalNoisePayload()
        return createNativeRaw16Buffer(
            route = "RAW10_NATIVE_MASTER",
            sourceFormat = android.graphics.ImageFormat.RAW10,
            buffers = buffers,
            characteristics = characteristics,
            payloadWhiteLevel = payloadWhiteLevel,
            payloadBlackLevels = payloadBlackLevels,
            developedWhiteLevel = developedWhiteLevel,
            developedBlackLevels = developedBlackLevels,
            maxFramesCap = maxFramesCap,
            frameExposureScales = exposureScaleToAnchor
        ) { selected, selectedExposureScales, cfa, whiteLevel, blackLevels, developedWhite, developedBlack, crop ->
        mergeNativeRaw10DirectRaw16(
            lensId = lensId,
            buffers = selected,
            cfaPattern = cfa,
            whiteLevel = whiteLevel,
            blackLevelArray = blackLevels,
            developedWhiteLevel = developedWhite,
            developedBlackLevelArray = developedBlack,
            sourceCrop = crop,
            maxFramesCap = maxFramesCap.coerceAtLeast(1),
            maxShiftPixels = maxShiftPixels,
            alignmentStrictness = alignmentStrictness,
            physicalNoiseSo = physicalTemporalNoise.toInterleavedSoOrEmpty(),
            fuseSupportFrames = fuseSupportFrames,
            exposureScaleToAnchor = selectedExposureScales,
            computationalHdr = computationalHdr
            )
        }
    }

    fun mergeRawSensorNativeRaw16Safe(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        characteristics: CameraCharacteristics,
        payloadWhiteLevel: Int,
        payloadBlackLevels: IntArray,
        maxFramesCap: Int = 3,
        maxShiftPixels: Int = 150,
        alignmentStrictness: Float = 0.8f,
        finalCalibration: FinalSensorCalibration? = null,
        fuseSupportFrames: Boolean = true,
        exposureScaleToAnchor: FloatArray = FloatArray(buffers.size) { 1f },
        computationalHdr: Boolean = false,
        developedWhiteLevel: Int = payloadWhiteLevel,
        developedBlackLevels: IntArray = payloadBlackLevels
    ): NativeRaw16Buffer? {
        val physicalTemporalNoise = finalCalibration.toPhysicalTemporalNoisePayload()
        return createNativeRaw16Buffer(
            route = "RAW_SENSOR_NATIVE_MASTER",
            sourceFormat = android.graphics.ImageFormat.RAW_SENSOR,
            buffers = buffers,
            characteristics = characteristics,
            payloadWhiteLevel = payloadWhiteLevel,
            payloadBlackLevels = payloadBlackLevels,
            developedWhiteLevel = developedWhiteLevel,
            developedBlackLevels = developedBlackLevels,
            maxFramesCap = maxFramesCap,
            frameExposureScales = exposureScaleToAnchor
        ) { selected, selectedExposureScales, cfa, whiteLevel, blackLevels, developedWhite, developedBlack, crop ->
        mergeNativeRawSensorDirectRaw16(
            lensId = lensId,
            buffers = selected,
            cfaPattern = cfa,
            whiteLevel = whiteLevel,
            blackLevelArray = blackLevels,
            developedWhiteLevel = developedWhite,
            developedBlackLevelArray = developedBlack,
            sourceCrop = crop,
            maxFramesCap = maxFramesCap.coerceAtLeast(1),
            maxShiftPixels = maxShiftPixels,
            alignmentStrictness = alignmentStrictness,
            physicalNoiseSo = physicalTemporalNoise.toInterleavedSoOrEmpty(),
            fuseSupportFrames = fuseSupportFrames,
            exposureScaleToAnchor = selectedExposureScales,
            computationalHdr = computationalHdr
            )
        }
    }

    private fun createNativeRaw16Buffer(
        route: String,
        sourceFormat: Int,
        buffers: Array<HardwareBuffer>,
        characteristics: CameraCharacteristics,
        payloadWhiteLevel: Int,
        payloadBlackLevels: IntArray,
        developedWhiteLevel: Int,
        developedBlackLevels: IntArray,
        maxFramesCap: Int,
        frameExposureScales: FloatArray,
        nativeMerge: (Array<HardwareBuffer>, FloatArray, Int, Int, IntArray, Int, IntArray, IntArray) -> ByteBuffer?
    ): NativeRaw16Buffer? {
        val selectedCount = minOf(buffers.size, maxFramesCap.coerceAtLeast(1))
        val selected = buffers.takeLast(selectedCount).toTypedArray()
        val selectedExposureScales = if (frameExposureScales.size == buffers.size) {
            frameExposureScales.takeLast(selectedCount).map { if (it.isFinite() && it > 0f) it else 1f }.toFloatArray()
        } else {
            FloatArray(selectedCount) { 1f }
        }
        if (selected.isEmpty()) return null
        val anchor = selected.last()
        val spatial = SensorToRawBufferTransform.create(
            characteristics = characteristics,
            bufferWidth = anchor.width,
            bufferHeight = anchor.height
        )
        // The runtime profile is refined from the first real Camera2 Image before it is offered
        // to the preview/ring-buffer path. Reuse that exact visible payload rect here so RAW
        // preview, Vulkan canonicalization/merge and the RAW16 DNG master share one crop contract.
        val runtimeGeometry = com.bncam.core.runtime.RawPipelineRuntimeOwner.getProfile()
            ?.takeIf { profile ->
                profile.format == sourceFormat &&
                    profile.geometry.bufferWidth == anchor.width &&
                    profile.geometry.bufferHeight == anchor.height
            }
            ?.geometry
        val cropLeft = runtimeGeometry?.cropLeft ?: spatial.bufferCropLeft
        val cropTop = runtimeGeometry?.cropTop ?: spatial.bufferCropTop
        val cropWidth = runtimeGeometry?.cropWidth ?: spatial.bufferCropWidth
        val cropHeight = runtimeGeometry?.cropHeight ?: spatial.bufferCropHeight
        val crop = intArrayOf(cropLeft, cropTop, cropWidth, cropHeight)
        if (runtimeGeometry != null) {
            val event =
                "route=$route format=$sourceFormat buffer=${anchor.width}x${anchor.height} " +
                    "crop=$cropLeft,$cropTop,${cropWidth}x$cropHeight " +
                    "baseline=${spatial.bufferCropLeft},${spatial.bufferCropTop}," +
                    "${spatial.bufferCropWidth}x${spatial.bufferCropHeight}"
            Log.i(TAG, "RAW_VISIBLE_PAYLOAD_CONSUMED $event")
            com.bncam.core.debug.DeviceTelemetryLogger.logEvent("RAW_VISIBLE_PAYLOAD_CONSUMED", event)
        }
        val baseCfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val phaseX = (runtimeGeometry?.cfaPhaseX ?: spatial.cfaOffsetX) and 1
        val phaseY = (runtimeGeometry?.cfaPhaseY ?: spatial.cfaOffsetY) and 1
        val phaseMask = phaseX or (phaseY shl 1)
        val localCfa = baseCfa xor phaseMask
        val whiteLevel = payloadWhiteLevel.coerceIn(1, 65535)
        val baseBlackLevels = payloadBlackLevels.takeIf { it.size >= 4 } ?: intArrayOf(0, 0, 0, 0)
        val localBlackLevels = if (phaseMask == 0) {
            baseBlackLevels
        } else {
            IntArray(4) { localSite ->
                val localX = localSite and 1
                val localY = (localSite shr 1) and 1
                val sourceSite = ((localY xor phaseY) shl 1) or (localX xor phaseX)
                baseBlackLevels[sourceSite]
            }
        }
        val developedWhite = developedWhiteLevel.coerceIn(1, 65535)
        val baseDevelopedBlack = developedBlackLevels.takeIf { it.size >= 4 } ?: localBlackLevels
        val localDevelopedBlack = if (phaseMask == 0) {
            baseDevelopedBlack
        } else {
            IntArray(4) { localSite ->
                val localX = localSite and 1
                val localY = (localSite shr 1) and 1
                val sourceSite = ((localY xor phaseY) shl 1) or (localX xor phaseX)
                baseDevelopedBlack[sourceSite]
            }
        }
        return invokeNativeSafely(route) {
            val directBuffer = nativeMerge(
                selected, selectedExposureScales, localCfa, whiteLevel, localBlackLevels,
                developedWhite, localDevelopedBlack, crop
            )
                ?: return@invokeNativeSafely null
            val byteCount = directBuffer.capacity()
            val expectedBytes = cropWidth.toLong() * cropHeight.toLong() * 2L
            if (!directBuffer.isDirect || byteCount <= 0 || byteCount.toLong() != expectedBytes) {
                runCatching { releaseNativeRaw16Buffer(directBuffer) }
                return@invokeNativeSafely null
            }
            NativeRaw16Buffer(
                directBuffer = directBuffer,
                byteCount = byteCount,
                width = cropWidth,
                height = cropHeight,
                sourceCropLeft = cropLeft,
                sourceCropTop = cropTop
            )
        }
    }

    /** Explicit compatibility helper. It materializes only when an older caller truly requests bytes. */
    fun mergeRaw10DngRaw16Safe(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        characteristics: CameraCharacteristics,
        payloadWhiteLevel: Int,
        payloadBlackLevels: IntArray,
        maxFramesCap: Int = 16,
        maxShiftPixels: Int = 150,
        alignmentStrictness: Float = 0.8f
    ): ByteArray? = mergeRaw10NativeRaw16Safe(
        lensId, buffers, characteristics, payloadWhiteLevel, payloadBlackLevels,
        maxFramesCap, maxShiftPixels, alignmentStrictness
    )?.use { it.materializeForDng() }

    fun mergeRawSensorDngRaw16Safe(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        characteristics: CameraCharacteristics,
        payloadWhiteLevel: Int,
        payloadBlackLevels: IntArray,
        maxFramesCap: Int = 3,
        maxShiftPixels: Int = 150,
        alignmentStrictness: Float = 0.8f
    ): ByteArray? = mergeRawSensorNativeRaw16Safe(
        lensId, buffers, characteristics, payloadWhiteLevel, payloadBlackLevels,
        maxFramesCap, maxShiftPixels, alignmentStrictness
    )?.use { it.materializeForDng() }

    internal fun releaseNativeRaw16BufferSafe(buffer: ByteBuffer) {
        runCatching { releaseNativeRaw16Buffer(buffer) }
            .onFailure { Log.e(TAG, "Native RAW16 buffer release failed", it) }
    }

    fun nativeRaw16OutstandingBufferCountSafe(): Int {
        if (!nativeEngineAvailable) return -1
        return runCatching { getNativeRaw16OutstandingBufferCountNative() }
            .onFailure { Log.e(TAG, "Native RAW16 outstanding-count query failed", it) }
            .getOrDefault(-1)
    }

    /**
     * Phase 2 compact Vulkan-primary YUV exposure statistics. The caller only packs a
     * regular Y/U/V sample grid; RGB conversion, histogramming and clipping stay on GPU.
     * Returns null when Vulkan is unavailable so the caller can use its explicit fail-safe path.
     */
    fun analyzeYuvExposureStatistics(
        packedSamples: ByteBuffer,
        sampleWidth: Int,
        sampleHeight: Int
    ): IntArray? {
        if (!nativeEngineAvailable || !packedSamples.isDirect || sampleWidth <= 0 || sampleHeight <= 0) return null
        return try {
            analyzeYuvExposureStatisticsNative(packedSamples, sampleWidth, sampleHeight)
        } catch (t: Throwable) {
            Log.w(TAG, "Vulkan YUV exposure statistics unavailable", t)
            null
        }
    }

    fun lastYuvStats(): String {
        val failure = nativeLoadFailure
        if (failure != null) {
            return "nativeEngineAvailable=false;failure=${failure.javaClass.simpleName}: ${failure.message}"
        }
        return try {
            getLastYuvStatsNative()
        } catch (t: Throwable) {
            "statsReadFailed=${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun lastDngMergeStats(): String {
        val failure = nativeLoadFailure
        if (failure != null) {
            return "nativeEngineAvailable=false;failure=${failure.javaClass.simpleName}: ${failure.message}"
        }
        return try {
            getLastDngMergeStatsNative()
        } catch (t: Throwable) {
            "statsReadFailed=${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun lastMasterIspStats(): String {
        val failure = nativeLoadFailure
        if (failure != null) {
            return "nativeEngineAvailable=false;failure=${failure.javaClass.simpleName}: ${failure.message}"
        }
        return try {
            getLastMasterIspStatsNative()
        } catch (t: Throwable) {
            "statsReadFailed=${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private inline fun <T> invokeNativeSafely(
        routeLabel: String,
        block: () -> T?
    ): T? {
        val failure = nativeLoadFailure
        if (failure != null) {
            Log.e(
                TAG,
                "Native $routeLabel skipped because native engine is unavailable: " +
                        "${failure.javaClass.simpleName}: ${failure.message}"
            )
            return null
        }

        return try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Native $routeLabel processing failed", t)
            null
        }
    }

    // =====================================================================================
    // NATIVE ENGINES (AHardwareBuffer pointers)
    // =====================================================================================

    @Suppress("unused")
    private external fun analyzeYuvExposureStatisticsNative(
        packedSamples: ByteBuffer,
        sampleWidth: Int,
        sampleHeight: Int
    ): IntArray?

    @Suppress("unused")
    private external fun processNativeYuv(
        buffers: Array<HardwareBuffer>,
        lensId: String,
        jpegQuality: Int,
        captureSensitivityIso: Int,
        rotationDegrees: Int,
        profileYuvWbRed: Float,
        profileYuvWbGreen: Float,
        profileYuvWbBlue: Float,
        profileColorSaturation: Float,
        profileColorContrast: Float,
        profilePresenceVibrance: Float,
        profileDetailAmount: Float,
        profileDetailRadius: Float,
        profileDetailDetail: Float,
        profileDetailMasking: Float,
        profileNrLuminance: Float,
        profileNrLuminanceDetail: Float,
        profileNrLuminanceContrast: Float,
        profileNrColor: Float,
        profileNrColorDetail: Float,
        profileNrColorSmoothness: Float,
        toneCurve: FloatArray,
        gammaCurve: FloatArray,
        sectionCurve: FloatArray,
        exposureScaleToAnchor: FloatArray,
        computationalHdr: Boolean,
        ultraHdrGainmapEnabled: Boolean,
        portraitEffectEnabled: Boolean,
        portraitMask: ByteBuffer?,
        portraitMaskWidth: Int,
        portraitMaskHeight: Int,
        portraitTargetLeft: Float,
        portraitTargetTop: Float,
        portraitTargetRight: Float,
        portraitTargetBottom: Float,
        portraitMaskRotationDegrees: Int
    ): ByteArray?

    @Suppress("unused")
    private external fun analyzeFrameCandidateNative(
        buffer: HardwareBuffer,
        sourceFormat: Int,
        nativeWhiteLevel: Int,
        nativeBlackLevels: IntArray
    ): FloatArray?



    @Suppress("unused")
    private external fun mergeNativeRaw10DirectRaw16(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevelArray: IntArray,
        developedWhiteLevel: Int,
        developedBlackLevelArray: IntArray,
        sourceCrop: IntArray,
        maxFramesCap: Int,
        maxShiftPixels: Int,
        alignmentStrictness: Float,
        physicalNoiseSo: DoubleArray,
        fuseSupportFrames: Boolean,
        exposureScaleToAnchor: FloatArray,
        computationalHdr: Boolean
    ): ByteBuffer?

    @Suppress("unused")
    private external fun mergeNativeRawSensorDirectRaw16(
        lensId: String,
        buffers: Array<HardwareBuffer>,
        cfaPattern: Int,
        whiteLevel: Int,
        blackLevelArray: IntArray,
        developedWhiteLevel: Int,
        developedBlackLevelArray: IntArray,
        sourceCrop: IntArray,
        maxFramesCap: Int,
        maxShiftPixels: Int,
        alignmentStrictness: Float,
        physicalNoiseSo: DoubleArray,
        fuseSupportFrames: Boolean,
        exposureScaleToAnchor: FloatArray,
        computationalHdr: Boolean
    ): ByteBuffer?

    @Suppress("unused")
    private external fun releaseNativeRaw16Buffer(buffer: ByteBuffer)

    @Suppress("unused")
    private external fun getNativeRaw16OutstandingBufferCountNative(): Int

    @Suppress("unused")
    private external fun getLastYuvStatsNative(): String

    @Suppress("unused")
    private external fun getLastDngMergeStatsNative(): String


    @Suppress("unused")
    private external fun getLastMasterIspStatsNative(): String

    @Suppress("unused")
    private external fun validateDemosaicNative(): String

    fun validateDemosaicImplementation(): String = validateDemosaicNative()

    @Suppress("unused")
    private external fun validateNoiseModelNative(lowNoiseSo: DoubleArray, highNoiseSo: DoubleArray): String

    fun validateNoiseModelImplementation(): String = validateNoiseModelNative(
        lowNoiseSo = doubleArrayOf(1.0e-6, 1.0e-8, 2.0e-6, 2.0e-8, 3.0e-6, 3.0e-8, 4.0e-6, 4.0e-8),
        highNoiseSo = doubleArrayOf(2.0e-4, 1.0e-5, 3.0e-4, 2.0e-5, 4.0e-4, 3.0e-5, 5.0e-4, 4.0e-5)
    )


    // =====================================================================================
    // Optional viewfinder-analysis conversion. Capture JPEG routes do not use this copy.
    // =====================================================================================

    fun yuv420ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        var pos = 0

        // 1. Kopieer Y (Luma) Plane, let op row strides!
        val yRowStride = yPlane.rowStride
        var yBufferPos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            // Gebruik repeat i.p.v. een for-loop met een ongebruikte 'row'
            repeat(height) {
                yBuffer.position(yBufferPos)
                yBuffer.get(nv21, pos, width)
                pos += width
                yBufferPos += yRowStride
            }
        }

        // 2. Kopieer UV (Chroma) Planes
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        // Check of we te maken hebben met de geoptimaliseerde Interleaved Pixel-layout
        if (uvPixelStride == 2 && uvRowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            val uvBytes = vBuffer.remaining()
            vBuffer.get(nv21, pos, uvBytes)
        } else {
            // Generic planar layout conversion.
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vPos = row * uvRowStride + col * uvPixelStride
                    val uPos = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[pos++] = vBuffer.get(vPos)
                    nv21[pos++] = uBuffer.get(uPos)
                }
            }
        }
        return nv21
    }

    @JvmStatic
    private external fun renderJpegFromMasterNative(
        lensId: String,
        raw16DirectBuffer: ByteBuffer,
        width: Int,
        height: Int,
        routeLabel: String,
        isRaw10: Boolean,
        rotationDegrees: Int,
        cfaPattern: Int,
        requestedDemosaicMode: Int,
        demosaicFallbackOccurred: Boolean,
        demosaicFallbackReason: String,
        demosaicFocusStabilityKnown: Boolean,
        demosaicFocusStabilityConfidence: Float,
        demosaicFocusSharpConfidence: Float,
        demosaicFocusMotionRisk: Float,
        demosaicFocusVelocityDioptersPerSec: Float,
        demosaicPredictiveAfConfidence: Float,
        demosaicPersonEvidenceKnown: Boolean,
        demosaicPersonConfidence: Float,
        demosaicDetectedFaceCount: Int,
        demosaicMaxFaceCoverage: Float,
        demosaicTemporalStabilityKnown: Boolean,
        demosaicTemporalStaticConfidence: Float,
        demosaicTemporalObserverConfidence: Float,
        demosaicTemporalMotionAcceptance: Float,
        demosaicTemporalAcceptedPairs: Int,
        captureSensitivityIso: Int,
        captureExposureTimeNs: Long,
        raw10UnpackMs: Float,
        raw10ToMasterRaw16Ms: Float,
        raw10MergeOrSingleMasterMs: Float,
        rawSensorReadMs: Float,
        rawSensorToMasterRaw16Ms: Float,
        blackLevelArray: FloatArray,
        whiteLevel: Int,
        sourceRowStrideBytes: Int,
        sourcePixelStrideBytes: Int,
        masterRowStrideBytes: Int,
        bufferOriginCfaOffsetX: Int,
        bufferOriginCfaOffsetY: Int,
        sensorInfoWhiteLevel: Int,
        sensorDynamicWhiteLevel: Int,
        sensorBlackLevelPattern: FloatArray,
        sensorDynamicBlackLevel: FloatArray?,
        chosenBlackLevelSource: String,
        chosenWhiteLevelSource: String,
        sourceBitDepth: Int,
        effectiveSourceRange: Int,
        masterStorageScale: Float,
        masterStorageLeftShift: Int,
        masterStorageContract: String,
        wbGains: FloatArray,
        wbFromMetadata: Boolean,
        awbCalibrationAuthority: Float,
        awbExplicitDevelopedAuthority: Boolean,
        awbManualGreenSplitAuthority: Boolean,
        colorMatrix: FloatArray,
        colorMatrixFromMetadata: Boolean,
        spectraProcessingEnabled: Boolean,
        physicalNoiseSo: DoubleArray,
        spectraPostRawSensitivityBoost: Int,
        profileSpectraLuma: Float,
        profileSpectraChroma: Float,
        profileSpectraDetailProtection: Float,
        profileSpectraLowFrequency: Float,
        profileToneExposure: Float,
        profileToneHighlights: Float,
        profileToneShadows: Float,
        profileToneWhites: Float,
        profileToneBlacks: Float,
        profileToneContrast: Float,
        profileLocalToneBias: Float,
        profileColorSaturation: Float,
        profileColorContrast: Float,
        profilePresenceVibrance: Float,
        profileDetailAmount: Float,
        profileDetailRadius: Float,
        profileDetailDetail: Float,
        profileDetailMasking: Float,
        knownHotPixelMap: IntArray,
        lensShadingMap: FloatArray,
        lensShadingColumns: Int,
        lensShadingRows: Int,
        lensShadingFromMetadata: Boolean,
        ultraHdrGainmapEnabled: Boolean,
        jpegQuality: Int,
        toneCurve: FloatArray,
        gammaCurve: FloatArray,
        sectionCurve: FloatArray,
        activeArray: IntArray?,
        cropRegion: IntArray?,
        preCorrectionArray: IntArray?,
        rawDebugDumpsEnabled: Boolean,
        rawDebugDumpDirectory: String,
        phoneAssistanceSensorsEnabled: Boolean,
        auxSensorValid: Boolean,
        auxCctKelvin: Float,
        auxContributionWeight: Float,
        portraitEffectEnabled: Boolean,
        portraitMask: ByteBuffer?,
        portraitMaskWidth: Int,
        portraitMaskHeight: Int,
        portraitTargetLeft: Float,
        portraitTargetTop: Float,
        portraitTargetRight: Float,
        portraitTargetBottom: Float,
        portraitMaskRotationDegrees: Int
    ): ByteArray?

    private external fun consumeLastUltraHdrGainmapArtifactNative(): ByteArray?

    private external fun packageUltraHdrJpegNative(
        baseJpeg: ByteArray,
        gainmapPixels: ByteArray,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        minContentBoost: Float,
        maxContentBoost: Float,
        gamma: Float,
        offsetSdr: Float,
        offsetHdr: Float
    ): ByteArray?


    fun updateHardwareConfigNative(settings: ResolvedLensHardwareSettings): Boolean {
        val failure = nativeLoadFailure
        if (failure != null) {
            Log.e(TAG, "Native lens hardware config push skipped: ${failure.javaClass.simpleName}: ${failure.message}")
            return false
        }
        return try {
            updateHardwareConfigNative(
                lensId = settings.lensId,
                blMode = settings.blackLevelNativeMode,
                dynamicBl = settings.dynamicBlackLevelPercent,
                manualBl = settings.nativeManualBlackLevels(),
                cmMode = settings.colorMatrixNativeMode,
                manualCm = settings.nativeManualColorMatrix(),
                awbMode = settings.awbNativeMode,
                awbRatio = settings.awbRatio,
                awbTemp = settings.awbTemp,
                awbIntensity = settings.awbIntensity
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Native lens hardware config push failed for ${settings.lensId}", t)
            false
        }
    }

    @JvmStatic
    external fun updateHardwareConfigNative(
        lensId: String,
        blMode: Int, dynamicBl: Float, manualBl: FloatArray?,
        cmMode: Int, manualCm: FloatArray?,
        awbMode: Int, awbRatio: Float, awbTemp: Float, awbIntensity: Float
    )

    @JvmStatic
    fun isOisStabilized(result: android.hardware.camera2.TotalCaptureResult?): Boolean {
        if (result == null) return true
        return try {
            validateOisStabilizedNative(result)
        } catch (e: Throwable) {
            true
        }
    }

    @JvmStatic
    private external fun validateOisStabilizedNative(result: android.hardware.camera2.TotalCaptureResult): Boolean

    @JvmStatic
    external fun getNativeStageHeartbeatJsonNative(): String

    private external fun getPreviewBufferTelemetryNative(): String

    private external fun retainRawPreviewHardwareBufferNative(buffer: HardwareBuffer): Long

    private external fun releaseRawPreviewHardwareBufferNative(handle: Long)

    private external fun getRawPreviewEglNextFrameIdNative(): Long

    private external fun getRawPreviewEglDisplayPresentTimeNative(frameId: Long): Long

    fun bindRawPreviewHardwareBufferToCurrentTexture(buffer: HardwareBuffer): Boolean {
        if (!nativeEngineAvailable) return false
        return runCatching { bindRawPreviewHardwareBufferToCurrentTextureNative(buffer) }.getOrDefault(false)
    }

    fun releaseRawPreviewEglImage(buffer: HardwareBuffer) {
        if (!nativeEngineAvailable) return
        runCatching { releaseRawPreviewEglImageNative(buffer) }
    }

    fun createRawPreviewGlFence(): Long {
        if (!nativeEngineAvailable) return 0L
        return runCatching { createRawPreviewGlFenceNative() }.getOrDefault(0L)
    }

    /** 1 = signaled/released, 0 = pending, -1 = failed/released. */
    fun pollRawPreviewGlFence(handle: Long): Int {
        if (!nativeEngineAvailable || handle == 0L) return -1
        return runCatching { pollRawPreviewGlFenceNative(handle) }.getOrDefault(-1)
    }

    fun pollRawPreview(
        frameSlotIndex: Int,
        submissionId: Long,
        previewWidth: Int,
        previewHeight: Int,
        cfaCellDecimation: Int,
        camera2PriorWbGains: FloatArray
    ): IntArray? {
        if (!nativeEngineAvailable || submissionId <= 0L) return null
        return try {
            pollRawPreviewNative(
                frameSlotIndex,
                submissionId.toInt(),
                (submissionId ushr 32).toInt(),
                previewWidth,
                previewHeight,
                cfaCellDecimation,
                camera2PriorWbGains
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun destroyRawPreviewGlFence(handle: Long) {
        if (!nativeEngineAvailable || handle == 0L) return
        runCatching { destroyRawPreviewGlFenceNative(handle) }
    }

    private external fun bindRawPreviewHardwareBufferToCurrentTextureNative(buffer: HardwareBuffer): Boolean
    private external fun releaseRawPreviewEglImageNative(buffer: HardwareBuffer)
    private external fun createRawPreviewGlFenceNative(): Long
    private external fun pollRawPreviewGlFenceNative(handle: Long): Int
    private external fun destroyRawPreviewGlFenceNative(handle: Long)

    private external fun renderRawPreviewNative(
        retainedHardwareBuffer: Long,
        sourceFormat: Int,
        cfaPattern: Int,
        requestedDemosaicMode: Int,
        blackLevels: FloatArray,
        whiteLevel: Int,
        wbGains: FloatArray,
        camera2PriorWbGains: FloatArray,
        colorMatrix: FloatArray,
        exposureGain: Float,
        captureSensitivityIso: Int,
        captureExposureTimeNs: Long,
        physicalGreenNoiseSo: FloatArray,
        focusDetailPriority: Float,
        profileToneExposure: Float,
        profileToneHighlights: Float,
        profileToneShadows: Float,
        profileToneWhites: Float,
        profileToneBlacks: Float,
        profileToneContrast: Float,
        profileLocalToneBias: Float,
        profileSaturation: Float,
        profileContrast: Float,
        profileVibrance: Float,
        profileDetailAmount: Float,
        profileDetailRadius: Float,
        profileDetailDetail: Float,
        profileDetailMasking: Float,
        profileNrLuminance: Float,
        profileNrLuminanceDetail: Float,
        profileNrLuminanceContrast: Float,
        profileNrColor: Float,
        profileNrColorDetail: Float,
        profileNrColorSmoothness: Float,
        toneCurve: FloatArray,
        gammaCurve: FloatArray,
        sectionCurve: FloatArray,
        rotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRowStrideBytes: Int,
        sourcePixelStrideBytes: Int,
        sourceCropLeft: Int,
        sourceCropTop: Int,
        sourceCropWidth: Int,
        sourceCropHeight: Int,
        outputHardwareBuffer: HardwareBuffer?,
        outputRgba: ByteBuffer,
        analysisNv21: ByteBuffer?,
        frameSlotIndex: Int,
        maxWidth: Int,
        maxHeight: Int,
        analysisReadbackRequested: Boolean
    ): IntArray?

    private external fun pollRawPreviewNative(
        frameSlotIndex: Int,
        submissionIdLow: Int,
        submissionIdHigh: Int,
        previewWidth: Int,
        previewHeight: Int,
        cfaCellDecimation: Int,
        camera2PriorWbGains: FloatArray
    ): IntArray?
}
