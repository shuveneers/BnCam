package com.bncam.data.profile

import java.util.Locale
import kotlin.math.roundToInt

enum class IspControlSpec(
    val min: Float,
    val max: Float,
    val defaultVal: Float,
    val step: Float
) {
    SIGNED_ADJUSTMENT(-1.00f, 1.00f, 0.00f, 0.01f),
    POSITIVE_GAIN(0.00f, 1.00f, 0.00f, 0.01f);

    fun displayString(value: Float): String = String.format(Locale.US, "%.2f", (value * 100.0f).roundToInt() / 100.0f)

    fun mapToEffective(uiValue: Float, baselineMin: Float, baselineDefault: Float, baselineMax: Float): Float {
        val clamped = (uiValue * 100.0f).roundToInt() / 100.0f
        return when (this) {
            SIGNED_ADJUSTMENT -> {
                if (clamped >= 0.00f) {
                    baselineDefault + clamped * (baselineMax - baselineDefault)
                } else {
                    baselineDefault + clamped * (baselineDefault - baselineMin)
                }
            }
            POSITIVE_GAIN -> {
                baselineDefault + clamped.coerceIn(0.00f, 1.00f) * (baselineMax - baselineDefault)
            }
        }
    }
}

enum class IspControlType {
    SIGNED_ADJUSTMENT,
    POSITIVE_GAIN,
    DISCRETE_INT,
    DISCRETE_ENUM,
    CURVE
}

data class IspControlValue(
    val key: String,
    val controlType: IspControlType,
    val uiValue: Float,
    val uiMinimum: Float,
    val uiMaximum: Float,
    val uiDefault: Float,
    val effectiveMinimum: Float,
    val effectiveBaseline: Float,
    val effectiveMaximum: Float,
    val impacts: Set<SettingImpact>
) {
    init {
        when (controlType) {
            IspControlType.SIGNED_ADJUSTMENT -> {
                require(uiMinimum == -1.00f) { "Signed adjustment uiMinimum must be -1.00" }
                require(uiMaximum == 1.00f) { "Signed adjustment uiMaximum must be 1.00" }
                require(uiDefault == 0.00f) { "Signed adjustment uiDefault must be 0.00" }
                require(uiValue in -1.00f..1.00f) { "Signed adjustment value $uiValue out of bounds [-1.00, 1.00]" }
            }
            IspControlType.POSITIVE_GAIN -> {
                require(uiMinimum == 0.00f) { "Positive gain uiMinimum must be 0.00" }
                require(uiMaximum == 1.00f) { "Positive gain uiMaximum must be 1.00" }
                require(uiDefault == 0.00f) { "Positive gain uiDefault must be 0.00" }
                require(uiValue in 0.00f..1.00f) { "Positive gain value $uiValue out of bounds [0.00, 1.00]" }
            }
            else -> {
                require(uiValue in uiMinimum..uiMaximum) { "Value $uiValue out of bounds [$uiMinimum, $uiMaximum]" }
            }
        }
    }

    val displayString: String get() = String.format(Locale.US, "%.2f", format2(uiValue))

    val effectiveVulkanValue: Float
        get() = mapToEffective(uiValue, controlType, effectiveMinimum, effectiveBaseline, effectiveMaximum)

    companion object {
        fun format2(value: Float): Float {
            return (value * 100.0f).roundToInt() / 100.0f
        }

        fun mapToEffective(
            uiValue: Float,
            controlType: IspControlType,
            effMin: Float,
            effBase: Float,
            effMax: Float
        ): Float {
            val clamped = format2(uiValue)
            return when (controlType) {
                IspControlType.SIGNED_ADJUSTMENT -> {
                    if (clamped >= 0.00f) {
                        effBase + clamped * (effMax - effBase)
                    } else {
                        effBase + clamped * (effBase - effMin)
                    }
                }
                IspControlType.POSITIVE_GAIN -> {
                    effBase + clamped.coerceIn(0.00f, 1.00f) * (effMax - effBase)
                }
                else -> clamped
            }
        }

        fun createSigned(
            key: String,
            uiValue: Float,
            effMin: Float,
            effBase: Float,
            effMax: Float,
            impacts: Set<SettingImpact>
        ): IspControlValue = IspControlValue(
            key = key,
            controlType = IspControlType.SIGNED_ADJUSTMENT,
            uiValue = format2(uiValue),
            uiMinimum = -1.00f,
            uiMaximum = 1.00f,
            uiDefault = 0.00f,
            effectiveMinimum = effMin,
            effectiveBaseline = effBase,
            effectiveMaximum = effMax,
            impacts = impacts
        )

        fun createGain(
            key: String,
            uiValue: Float,
            effMin: Float,
            effBase: Float,
            effMax: Float,
            impacts: Set<SettingImpact>
        ): IspControlValue = IspControlValue(
            key = key,
            controlType = IspControlType.POSITIVE_GAIN,
            uiValue = format2(uiValue),
            uiMinimum = 0.00f,
            uiMaximum = 1.00f,
            uiDefault = 0.00f,
            effectiveMinimum = effMin,
            effectiveBaseline = effBase,
            effectiveMaximum = effMax,
            impacts = impacts
        )
    }
}
