package com.bncam.data.settings

enum class ViewfinderSliderAssignment(val displayName: String) {
    OFF("Off"),
    FOCUS("Focus"),
    EV("EV"),
    WHITE_BALANCE("White balance"),
    SATURATION("Saturation"),
    CONTRAST("Contrast");

    companion object {
        val selectable: List<ViewfinderSliderAssignment> = entries

        fun fromPersisted(value: String?): ViewfinderSliderAssignment = when (value?.trim()?.lowercase()) {
            "focus", "af" -> FOCUS
            "ev", "exposure", "exposure compensation" -> EV
            "iso", "shutter", "shutter speed", "shutterspeed" -> OFF
            "white balance", "white_balance", "wb" -> WHITE_BALANCE
            "saturation", "sat" -> SATURATION
            "contrast" -> CONTRAST
            "zoom" -> OFF
            else -> OFF
        }
    }
}
