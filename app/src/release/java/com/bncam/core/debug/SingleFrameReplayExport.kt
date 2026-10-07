package com.bncam.core.debug

import android.content.Context
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.isp.raw.Raw16RenderInput

object SingleFrameReplayExport {
    fun exportIfRequested(context: Context, input: Raw16RenderInput, recipe: CaptureRecipe) = Unit
}
