package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraPhase10FullResidencySourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    private fun source(app: File, path: String): String = File(app, path).readText()

    @Test
    fun `pass0 is vulkan primary and cpu pixel mutation is failure only`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")
        val block = core.substringAfter("const auto pass0Start")
            .substringBefore("pass0State.processingTimeMs = elapsedMs(pass0Start)")
        val shader = source(app, "src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp")
        val backend = source(app, "src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp")

        val gpu = block.indexOf("tryApplySpectraPass0Vulkan")
        val fallback = block.indexOf("if (!pass0GpuApplied)")
        val cpu = block.indexOf("applySpectraPass0(workingRaw")
        assertTrue(gpu >= 0 && fallback > gpu && cpu > fallback)
        assertTrue(block.substring(fallback, cpu).contains("spectraPass0CpuPixelMutation = true"))
        assertTrue(block.substring(fallback, cpu).contains("++spectraFullFrameCpuClones"))
        assertTrue(shader.contains("pc.mode == 5u"))
        assertTrue(shader.contains("candidateMosaic[index] = original + bias"))
        assertTrue(backend.contains("request.pass0Only"))
        assertTrue(backend.contains("result.pass0Only = request.pass0Only"))
    }

    @Test
    fun `pass0 pass1 and pass2 keep the full raw resident on vulkan success`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")
        val chroma = source(app, "src/main/cpp/vulkan/VulkanSpectraResidentChromaBackend.cpp")

        assertTrue(core.contains("pass0ResidentResult.residentOutputGeneration"))
        assertTrue(core.contains("tryApplySpectraPass1Vulkan("))
        assertTrue(core.contains("pass0ResidentGeneration"))
        assertTrue(core.contains("tryApplySpectraPass2Vulkan("))
        assertTrue(core.contains("pass1ResidentGeneration"))
        assertTrue(core.contains("true, &pass2ResidentResult"))
        assertTrue(chroma.contains("if (!request.deferFullFrameReadback)"))
        assertTrue(chroma.contains("result.fullFrameReadbackDeferred = request.deferFullFrameReadback"))
    }

    @Test
    fun `post pass2 planning uses compact gpu observation instead of full frame host materialization`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")
        val planner = source(app, "src/main/cpp/vulkan/VulkanSpectraPass3PlannerBackend.cpp")
        val shader = source(app, "src/main/cpp/vulkan/shaders/spectra_pass3_planner.comp")

        assertTrue(core.contains("runSpectraResidentCompactPlanner"))
        assertTrue(core.contains("residentCompactObservationReady"))
        assertTrue(core.contains("OK_GPU_RESIDENT_COMPACT"))
        assertTrue(planner.contains("vkCmdFillBuffer(commandBuffer_, output_.buffer"))
        assertTrue(planner.contains("push.mode = 3u"))
        assertTrue(planner.contains("result.rawStatistics"))
        assertTrue(planner.contains("result.chromaBands"))
        assertTrue(shader.contains("pc.mode == 3u"))
    }

    @Test
    fun `raw finalize consumes the latest active resident spectra generation`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")
        val finalize = core.substringAfter("bncam::vulkan::SpectraRawFinalizeRequest request")
            .substringBefore("AutoDemosaicContext autoContext")

        assertTrue(finalize.contains("pass3ResidentActive"))
        assertTrue(finalize.contains("pass2ResidentActive"))
        assertTrue(finalize.contains("pass1ResidentActive"))
        assertTrue(finalize.contains("executeSpectraRawFinalizeFromChroma"))
        assertTrue(finalize.contains("executeSpectraRawFinalizeFromPreDemosaic"))
    }

    @Test
    fun `resident failure recovery never silently uses stale host raw`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")
        val runtime = source(app, "src/main/cpp/vulkan/VulkanRuntime.cpp")
        val backend = source(app, "src/main/cpp/vulkan/VulkanSpectraRawFinalizeBackend.cpp")

        assertTrue(core.contains("materializeLatestSpectraResidentForCpuFinalize"))
        assertTrue(core.contains("exact failure-recovery readback was unavailable"))
        assertTrue(core.contains("materializeRawFinalizeResidentForCpuDemosaic"))
        assertTrue(core.contains("readbackSpectraRawFinalizeResident"))
        assertTrue(core.contains("jpegRawCpuFinalized = true"))
        assertTrue(core.contains("exact finalized RAW recovery was unavailable"))
        assertTrue(runtime.contains("spectraRawFinalizeBackend_.readbackResidentOutput"))
        assertTrue(backend.contains("vkCmdCopyBuffer(commandBuffer_, output_.buffer, readback_.buffer"))
    }

    @Test
    fun `phase10 exposes explicit zero cpu residency acceptance telemetry`() {
        val app = appDir()
        val core = source(app, "src/main/cpp/IspCore.cpp")

        assertTrue(core.contains("spectraPass0CpuPixelMutation="))
        assertTrue(core.contains("spectraFullFrameCpuReadbacks="))
        assertTrue(core.contains("spectraFullFrameCpuClones="))
        assertTrue(core.contains("spectraResidentCompactObserverUsed="))
        assertTrue(core.contains("spectraPass0VulkanUsedForOutput="))
        assertTrue(core.contains("spectraPass0VulkanResidentGeneration="))

        val pass0 = core.substringAfter("const auto pass0Start")
            .substringBefore("pass0State.processingTimeMs = elapsedMs(pass0Start)")
        assertFalse(pass0.substringBefore("if (!pass0GpuApplied)").contains("workingRaw.mosaic.clone()"))
    }
}
