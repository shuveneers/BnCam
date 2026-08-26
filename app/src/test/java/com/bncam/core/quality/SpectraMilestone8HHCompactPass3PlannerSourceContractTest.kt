package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone8HHCompactPass3PlannerSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun `pass3 planner is a real compact vulkan dispatch`() {
        val app = appDir()
        val cmake = File(app, "src/main/cpp/CMakeLists.txt").readText()
        val shader = File(app, "src/main/cpp/vulkan/shaders/spectra_pass3_planner.comp").readText()
        val backend = File(app, "src/main/cpp/vulkan/VulkanSpectraPass3PlannerBackend.cpp").readText()

        assertTrue(cmake.contains("VulkanSpectraPass3PlannerBackend.cpp"))
        assertTrue(cmake.contains("spectra_pass3_planner.comp"))
        assertTrue(cmake.contains("getSpectraPass3PlannerSpirv"))
        assertTrue(shader.contains("void planRow()"))
        assertTrue(shader.contains("void planColumn()"))
        assertTrue(shader.contains("void planLowCell()"))
        assertTrue(backend.contains("executeFromResident"))
        assertTrue(backend.contains("PASS3_GPU_COMPACT_PLANNER_READY"))
        assertFalse(backend.contains("outputMosaic.resize"))
    }

    @Test
    fun `runtime resolves pass2 resident generation without exposing vulkan handles to isp`() {
        val app = appDir()
        val runtime = File(app, "src/main/cpp/vulkan/VulkanRuntime.cpp").readText()
        val header = File(app, "src/main/cpp/vulkan/VulkanRuntime.h").readText()

        assertTrue(header.contains("executeSpectraPass3PlannerFromPass2"))
        val block = runtime.substringAfter("VulkanRuntime::executeSpectraPass3PlannerFromPass2")
            .substringBefore("VulkanRuntime::executeSpectraTemporalObserver")
        assertTrue(block.contains("spectraResidentChromaBackend_.resolveResidentOutput"))
        assertTrue(block.contains("spectraPass3PlannerBackend_.executeFromResident"))
        assertTrue(block.contains("residentWidth != request.frameWidth"))
    }

    @Test
    fun `isp reuses one gpu compact observation before exact cpu fallback`() {
        val app = appDir()
        val core = File(app, "src/main/cpp/IspCore.cpp").readText()
        val observation = core.substringAfter("const bool residentPostPass2Available")
            .substringBefore("SpectraPass3State pass3State{}")
        val planning = core.substringAfter("SpectraPass3State pass3State{}")
            .substringBefore("pass3State.lowBand.plan = lowBandPlan")

        assertTrue(core.contains("computePass3StateVulkanCompact"))
        assertTrue(observation.contains("computePass3StateVulkanCompact"))
        assertTrue(observation.contains("residentPlannerAttempt"))
        assertTrue(observation.contains("residentCompactObservationReady"))
        assertTrue(planning.contains("pass3State = std::move(residentPlannerAttempt)"))
        assertTrue(planning.contains("pass3State = computePass3State(workingRaw, workingMeta, uiConfig)"))
        assertTrue(core.indexOf("computePass3StateVulkanCompact") <
                core.indexOf("pass3State = computePass3State(workingRaw"))
        assertTrue(core.contains("plannerGpuAttempted="))
        assertTrue(core.contains("plannerGpuUsed="))
        assertTrue(core.contains("plannerCompactBytes="))
        assertTrue(core.contains("COMPACT_PLANNER_RAW_FINALIZE_DEMOSAIC_AWB_CCM_POST_DEMOSAIC"))
    }
}
