package com.bncam.ui.screens.capture

import com.bncam.core.engine.ImageUtils

/** Boundary around GL-fence primitives so failure behavior can be exercised deterministically. */
interface RawPreviewFenceBackend {
    fun createFence(): Long
    fun pollFence(handle: Long): Int
    fun destroyFence(handle: Long)
}

internal object PlatformRawPreviewFenceBackend : RawPreviewFenceBackend {
    override fun createFence(): Long = ImageUtils.createRawPreviewGlFence()
    override fun pollFence(handle: Long): Int = ImageUtils.pollRawPreviewGlFence(handle)
    override fun destroyFence(handle: Long) = ImageUtils.destroyRawPreviewGlFence(handle)
}
