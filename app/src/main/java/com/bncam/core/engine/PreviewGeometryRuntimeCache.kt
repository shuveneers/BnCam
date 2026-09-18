package com.bncam.core.engine

/**
 * Runtime-only cache for preview SurfaceTexture geometries that have been proven healthy on the
 * exact active camera route + canonical capture stream.
 *
 * Persistence is deliberately avoided. Camera/HAL capabilities may change across reboot, vendor
 * update or process lifetime, so every cached geometry is revalidated against the current
 * CameraCharacteristics + CameraDeviceSetup contract before reuse.
 */
class PreviewGeometryRuntimeCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    data class Key(
        val selectedLensId: String,
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val cameraRouteKind: String,
        val backendRoute: String,
        val captureFormat: Int,
        val captureWidth: Int,
        val captureHeight: Int,
        val vendorConfigSignature: String,
        val rawPreviewBindingSignature: String
    )

    data class Geometry(
        val width: Int,
        val height: Int
    ) {
        val valid: Boolean get() = width > 0 && height > 0
        val key: String get() = "${width}x${height}"
    }

    private val entries = LinkedHashMap<Key, Geometry>()

    @Synchronized
    fun get(key: Key): Geometry? = entries[key]

    @Synchronized
    fun promote(key: Key, geometry: Geometry) {
        if (!geometry.valid || maxEntries <= 0) return
        entries.remove(key)
        entries[key] = geometry
        while (entries.size > maxEntries) {
            val eldest = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldest)
        }
    }

    @Synchronized
    fun invalidate(key: Key): Geometry? = entries.remove(key)

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 24
    }
}
