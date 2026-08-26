package com.bncam.core.gpu

object GpuCapabilityProfiler {

    external fun nativeQueryVulkanCapabilities(): String
    external fun nativeRunCpuVsGpuBenchmark(workloadId: Int, runCount: Int): String
    external fun nativeGetCpuPipelineProfile(): String

    fun queryVulkanCapabilities(): String {
        return try {
            nativeQueryVulkanCapabilities()
        } catch (t: Throwable) {
            "{\"status\":\"ERROR\",\"reason\":\"" + (t.message ?: "JNI error") + "\"}"
        }
    }

    fun runBenchmark(workloadId: Int, runCount: Int = 10): String {
        return try {
            nativeRunCpuVsGpuBenchmark(workloadId, runCount)
        } catch (t: Throwable) {
            "{\"status\":\"ERROR\",\"reason\":\"" + (t.message ?: "JNI error") + "\"}"
        }
    }

    fun getCpuPipelineProfile(): String {
        return try {
            nativeGetCpuPipelineProfile()
        } catch (t: Throwable) {
            "{\"status\":\"ERROR\",\"reason\":\"" + (t.message ?: "JNI error") + "\"}"
        }
    }
}
