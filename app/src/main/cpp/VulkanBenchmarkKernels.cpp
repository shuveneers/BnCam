#include "VulkanBenchmarkKernels.h"
#include "vulkan/VulkanJson.h"
#include "vulkan/VulkanRuntime.h"

#include <sstream>

BenchmarkRunResults VulkanBenchmarkKernels::runBenchmark(
    int workloadId,
    int runCount,
    const uint16_t* rawInput,
    int width,
    int height,
    int frameCount
) {
    (void) runCount;
    (void) rawInput;
    (void) width;
    (void) height;
    (void) frameCount;

    BenchmarkRunResults result;
    result.workloadId = workloadId;
    result.workloadName = "Retired CPU-vs-GPU benchmark compatibility endpoint";
    const auto runtime = bncam::vulkan::VulkanRuntime::instance().snapshot();
    result.isGpuAvailable = runtime.state == bncam::vulkan::RuntimeState::READY;

    std::ostringstream json;
    json << '{'
         << "\"status\":\"RETIRED\","
         << "\"reason\":\"CPU_vs_GPU_comparison_removed_before_mandatory_Vulkan_integration\","
         << "\"runtimeState\":" << bncam::vulkan::json::quote(bncam::vulkan::toString(runtime.state)) << ','
         << "\"runtimeIdentity\":" << bncam::vulkan::json::quote(runtime.runtimeIdentity) << ','
         << "\"activeProductionStages\":[]"
         << '}';
    result.summaryJson = json.str();
    return result;
}
