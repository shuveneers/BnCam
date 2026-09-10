package com.bncam.core.vulkan

import android.content.Context
import com.bncam.core.debug.DiagnosticsAggregator
import java.security.MessageDigest

/** Loads the release-approved SPECTRA package bundled in this APK into the process Vulkan runtime. */
internal object SpectraNeuralModelInstaller {
    const val MODEL_VERSION = "adaptive-v2-prod1"
    const val ASSET_PATH = "spectra_neural/spectra_neural_v2_adaptive.vkmodel"
    const val EXPECTED_SHA256 = "180dee8696fd80c37bcda43e9f5f205c52ce1d4892c59fd3721b8a73c4937852"

    fun loadBundled(context: Context): Boolean {
        val status = try {
            val bytes = context.applicationContext.assets.open(ASSET_PATH).use { it.readBytes() }
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            when {
                actualSha256 != EXPECTED_SHA256 ->
                    "REJECTED_CHECKSUM expected=$EXPECTED_SHA256 actual=$actualSha256"
                VulkanNativeBridge.nativeConfigureSpectraNeuralModel(bytes) ->
                    "LOADED version=$MODEL_VERSION sha256=$actualSha256 bytes=${bytes.size}"
                else ->
                    "REJECTED_NATIVE_CONTRACT_OR_RUNTIME version=$MODEL_VERSION sha256=$actualSha256"
            }
        } catch (failure: Throwable) {
            "LOAD_FAILED ${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
        }
        DiagnosticsAggregator.record(
            DiagnosticsAggregator.Stream.ISP,
            "APPLICATION",
            "SPECTRA NEURAL MODEL",
            status
        )
        return status.startsWith("LOADED ")
    }
}
