package com.bncam

import android.content.Context

/**
 * Process-scoped application Context holder for low-level capture contracts that cannot accept an
 * Android Context without widening hot-path APIs. Only applicationContext is retained.
 */
object BnCamProcessContext {
    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getOrNull(): Context? = applicationContext

    fun require(): Context = requireNotNull(applicationContext) {
        "BnCamProcessContext is not initialized"
    }
}
