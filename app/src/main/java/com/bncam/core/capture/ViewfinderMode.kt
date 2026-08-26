package com.bncam.core.capture

/**
 * Top-level camera experience selected by the viewfinder mode strip.
 *
 * This state is intentionally session-local and is not persisted by SettingsRepository. Photo is
 * therefore the deterministic startup mode for every fresh camera host. Capture profiles remain an
 * independent source of render/ISP settings underneath these experience modes.
 */
enum class ViewfinderMode(val label: String) {
    NIGHT("Night"),
    PHOTO("Photo"),
    PORTRAIT("Portrait"),
    VIDEO("Video")
}
