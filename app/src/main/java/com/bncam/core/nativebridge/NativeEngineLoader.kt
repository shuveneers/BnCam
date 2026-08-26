package com.bncam.core.nativebridge

import android.util.Log

/**
 * One authoritative loader for libc++_shared, OpenCV and libbncam.
 *
 * Native libraries are process-scoped. ImageUtils, Vulkan and camera sessions must not maintain
 * competing load state or call System.loadLibrary independently.
 */
object NativeEngineLoader {
    private const val TAG = "NativeEngineLoader"
    private val libraries = listOf("c++_shared", "opencv_java4", "bncam")

    @Volatile private var attempted = false
    @Volatile private var failure: Throwable? = null

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (attempted) return failure == null
        attempted = true
        for (library in libraries) {
            try {
                System.loadLibrary(library)
                Log.i(TAG, "Native library loaded: $library")
            } catch (problem: Throwable) {
                failure = problem
                Log.e(
                    TAG,
                    "Native load-chain failed at '$library'. Required ABI libraries must match.",
                    problem
                )
                return false
            }
        }
        Log.i(TAG, "BnCam native process load-chain ready")
        return true
    }

    val isAvailable: Boolean
        get() = ensureLoaded()

    fun failureOrNull(): Throwable? {
        ensureLoaded()
        return failure
    }

    fun status(): String {
        val problem = failureOrNull()
        return if (problem == null) {
            "OK"
        } else {
            "FAILED: ${problem.javaClass.simpleName}: ${problem.message}"
        }
    }

    internal fun attemptedForDiagnostics(): Boolean = attempted
}
