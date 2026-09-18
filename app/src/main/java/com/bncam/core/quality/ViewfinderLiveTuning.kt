package com.bncam.core.quality

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-local creative controls driven by the assignable viewfinder sliders.
 *
 * These values deliberately do not rewrite profile DataStore state. CaptureRecipeFactory snapshots
 * them at shutter time, so a capture remains deterministic even when the user moves a slider while
 * processing is already queued. Resetting a control returns to the active profile / Camera2 Auto.
 */
data class ViewfinderLiveTuningSnapshot(
    val whiteBalanceKelvin: Int? = null,
    val saturationOffset: Float = 0f,
    val contrastOffset: Float = 0f,
    val revision: Long = 0L
) {
    fun sanitized(): ViewfinderLiveTuningSnapshot = copy(
        whiteBalanceKelvin = whiteBalanceKelvin?.coerceIn(2000, 10000),
        saturationOffset = saturationOffset.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
        contrastOffset = contrastOffset.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    )

    val active: Boolean
        get() = whiteBalanceKelvin != null ||
            kotlin.math.abs(saturationOffset) > 1.0e-4f ||
            kotlin.math.abs(contrastOffset) > 1.0e-4f

    fun applyColor(base: ProfileColorTuning): ProfileColorTuning = base.copy(
        saturation = (base.saturation + saturationOffset).coerceIn(-1f, 1f),
        contrast = (base.contrast + contrastOffset).coerceIn(-1f, 1f)
    ).sanitized()


}

object ViewfinderLiveTuning {
    private val revision = AtomicLong(0L)
    private val mutableState = MutableStateFlow(ViewfinderLiveTuningSnapshot())
    val state: StateFlow<ViewfinderLiveTuningSnapshot> = mutableState

    fun snapshot(): ViewfinderLiveTuningSnapshot = mutableState.value.sanitized()

    fun setWhiteBalanceKelvin(kelvin: Int?) = update {
        copy(whiteBalanceKelvin = kelvin?.coerceIn(2000, 10000))
    }

    fun setSaturationOffset(value: Float) = update {
        copy(saturationOffset = value.coerceIn(-1f, 1f))
    }

    fun setContrastOffset(value: Float) = update {
        copy(contrastOffset = value.coerceIn(-1f, 1f))
    }

    fun resetWhiteBalance() = setWhiteBalanceKelvin(null)
    fun resetSaturation() = setSaturationOffset(0f)
    fun resetContrast() = setContrastOffset(0f)

    private inline fun update(transform: ViewfinderLiveTuningSnapshot.() -> ViewfinderLiveTuningSnapshot) {
        val nextRevision = revision.incrementAndGet()
        mutableState.value = mutableState.value.transform().sanitized().copy(revision = nextRevision)
    }
}
