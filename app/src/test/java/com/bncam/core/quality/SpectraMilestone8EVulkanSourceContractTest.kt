package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8EVulkanSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `production fp32 opponent kernel is connected behind shadow qualification`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraOpponentBackend.cpp").readText()
        val bytecode = File(app, "src/main/cpp/vulkan/VulkanShaderBytecode.h").readText()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmake.contains("vulkan/VulkanSpectraOpponentBackend.cpp"))
        assertTrue(runtime.contains("executeSpectraOpponentFeatures"))
        assertTrue(runtime.contains("SPECTRA_FP32_OPPONENT_FEATURES"))
        assertTrue(backend.contains("getSpectraOpponentFeaturesSpirv"))
        assertTrue(backend.contains("VMA_ALLOCATION_CREATE_MAPPED_BIT"))
        assertFalse(backend.contains("HOST_ACCESS_ALLOW_TRANSFER_INSTEAD"))
        assertTrue(bytecode.contains("patchedInstructionCount != 1"))
        assertTrue(core.contains("compareOpponentFeatureFrameToScalar"))
        assertTrue(core.contains("benchmarkOpponentFeaturesCpu"))
        assertTrue(core.contains("opponentBenchmarkNeeded || deviceQualifiedBeforeCapture"))
        assertTrue(core.contains("vulkanOpponentMemoryGatePassed"))
        assertTrue(core.contains("kMaximumVulkanOpponentTransientBytes"))
        assertTrue(core.contains("M8F_STRIPED_OPPONENT_STAGE_VULKAN_FP32_QUALIFIED_NEXT_CAPTURE"))
    }

    @Test
    fun `vulkan output is only consumed after prior complete device qualification`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val qualifier = File(app, "src/main/cpp/SpectraVulkanOpponentQualification.h").readText()

        assertTrue(qualifier.contains("deviceQualificationSelected"))
        assertTrue(qualifier.contains("samples_.size() >= 5u"))
        assertTrue(qualifier.contains("maximumAbsoluteDelta <= 3.0e-6f"))
        assertTrue(qualifier.contains("kMaximumFailedSamples = 3"))
        assertTrue(core.contains("useVulkanOpponentFrame = deviceQualifiedBeforeCapture"))
        assertTrue(core.contains("setDeviceQualificationSelected"))
        assertTrue(core.contains("VULKAN_EXECUTION_FAILED_CPU_FALLBACK"))
        assertTrue(core.contains("execution.timestampQueryUsed"))
        assertFalse(core.contains("PerformanceBackendKind::VulkanFp32;"))
    }

    @Test
    fun `schema 16 exports production opponent kernel evidence`() {
        val app = appDir()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue(schemaVersion >= 16)
        assertTrue(trace.contains("\"opponentFeatureKernel\" to linkedMapOf"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentUsedForOutput"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentQualificationOverheadMs"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentMemoryGatePassed"))
        assertTrue(trace.contains("spectraVisibleChromaVulkanOpponentCpuReferenceBackend"))
        assertTrue(core.contains("spectraPerformanceMilestone="))
        assertTrue(core.contains("spectraPerformanceVulkanOpponentUsedForOutput"))
    }

    @Test
    fun `m8e does not touch dng fusion jni or ui contracts`() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertFalse(merger.contains("VulkanSpectraOpponentBackend"))
        assertFalse(merger.contains("SpectraVulkanOpponentQualification"))
        assertFalse(nativeBridge.contains("VulkanSpectraOpponentBackend"))
        assertFalse(nativeBridge.contains("SpectraVulkanOpponentQualification"))
    }
}
