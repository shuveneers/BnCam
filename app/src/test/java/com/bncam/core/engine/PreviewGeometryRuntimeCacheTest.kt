package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewGeometryRuntimeCacheTest {
    private fun key(
        lens: String = "2",
        logical: String = "0",
        physical: String? = "2",
        routeKind: String = "LOGICAL_PHYSICAL",
        backendRoute: String = "logical_physical",
        format: Int = 32,
        width: Int = 4080,
        height: Int = 3072,
        vendor: String = "none",
        rawBinding: String = "none"
    ) = PreviewGeometryRuntimeCache.Key(
        selectedLensId = lens,
        logicalCameraId = logical,
        physicalCameraId = physical,
        cameraRouteKind = routeKind,
        backendRoute = backendRoute,
        captureFormat = format,
        captureWidth = width,
        captureHeight = height,
        vendorConfigSignature = vendor,
        rawPreviewBindingSignature = rawBinding
    )

    @Test
    fun `promotion is exact key scoped`() {
        val cache = PreviewGeometryRuntimeCache()
        val geometry = PreviewGeometryRuntimeCache.Geometry(1440, 1080)
        cache.promote(key(), geometry)

        assertEquals(geometry, cache.get(key()))
        assertNull(cache.get(key(format = 37)))
        assertNull(cache.get(key(width = 2040, height = 1536)))
        assertNull(cache.get(key(physical = "3")))
        assertNull(cache.get(key(routeKind = "DIRECT_CAMERA")))
        assertNull(cache.get(key(backendRoute = "direct")))
        assertNull(cache.get(key(vendor = "vendor_changed")))
        assertNull(cache.get(key(rawBinding = "custom_raw")))
    }

    @Test
    fun `invalid geometry is never cached`() {
        val cache = PreviewGeometryRuntimeCache()
        cache.promote(key(), PreviewGeometryRuntimeCache.Geometry(0, 1080))
        assertNull(cache.get(key()))
    }

    @Test
    fun `invalidate removes only exact stream plan`() {
        val cache = PreviewGeometryRuntimeCache()
        val a = key()
        val b = key(format = 37)
        cache.promote(a, PreviewGeometryRuntimeCache.Geometry(1440, 1080))
        cache.promote(b, PreviewGeometryRuntimeCache.Geometry(1280, 960))

        cache.invalidate(a)

        assertNull(cache.get(a))
        assertEquals(PreviewGeometryRuntimeCache.Geometry(1280, 960), cache.get(b))
    }

    @Test
    fun `cache is bounded`() {
        val cache = PreviewGeometryRuntimeCache(maxEntries = 2)
        val a = key(lens = "0")
        val b = key(lens = "1")
        val c = key(lens = "2")
        cache.promote(a, PreviewGeometryRuntimeCache.Geometry(1920, 1440))
        cache.promote(b, PreviewGeometryRuntimeCache.Geometry(1600, 1200))
        cache.promote(c, PreviewGeometryRuntimeCache.Geometry(1440, 1080))

        assertEquals(2, cache.size())
        assertNull(cache.get(a))
        assertEquals(PreviewGeometryRuntimeCache.Geometry(1600, 1200), cache.get(b))
        assertEquals(PreviewGeometryRuntimeCache.Geometry(1440, 1080), cache.get(c))
    }
}
