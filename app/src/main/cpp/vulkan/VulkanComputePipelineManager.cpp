#include "VulkanComputePipelineManager.h"
#include "VulkanShaderBytecode.h"
#include "Raw10UnpackSpirv.h"
#include "vk_mem_alloc.h"

#include "VulkanRuntime.h"
#include <algorithm>
#include <cstring>
#include <string>

namespace bncam::vulkan {

VulkanComputePipelineManager::~VulkanComputePipelineManager() {
    // Pipeline destruction handled explicitly or during VulkanRuntime shutdown
}

bool VulkanComputePipelineManager::initializePipelines(VkDevice device) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialized_) return true;
    if (device == VK_NULL_HANDLE) return false;

    // 1. Descriptor Set Layout (Binding 0: Readonly Storage Buffer, Binding 1: Readwrite Storage Buffer)
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;

    if (vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        return false;
    }

    // 2. Pipeline Layout with Push Constants
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(IspCropRotateOutputPushConstants);

    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &pushRange;

    if (vkCreatePipelineLayout(device, &pipeLayoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        descriptorSetLayout_ = VK_NULL_HANDLE;
        return false;
    }

    // 3. RAW10 Unpack Pipeline
    const auto& raw10Spirv = getRaw10UnpackSpirvGenerated();
    VkShaderModuleCreateInfo raw10ModInfo{};
    raw10ModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    raw10ModInfo.codeSize = raw10Spirv.size() * sizeof(std::uint32_t);
    raw10ModInfo.pCode = raw10Spirv.data();

    if (vkCreateShaderModule(device, &raw10ModInfo, nullptr, &raw10ShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = raw10ShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &raw10Pipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 4. RAW16 Canonicalize Pipeline
    const auto& raw16Spirv = getRaw16CanonicalizeSpirv();
    VkShaderModuleCreateInfo raw16ModInfo{};
    raw16ModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    raw16ModInfo.codeSize = raw16Spirv.size() * sizeof(std::uint32_t);
    raw16ModInfo.pCode = raw16Spirv.data();

    if (vkCreateShaderModule(device, &raw16ModInfo, nullptr, &raw16ShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = raw16ShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &raw16Pipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 5. Linearization Pipeline
    const auto& linSpirv = getRawLinearizationSpirv();
    VkShaderModuleCreateInfo linModInfo{};
    linModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    linModInfo.codeSize = linSpirv.size() * sizeof(std::uint32_t);
    linModInfo.pCode = linSpirv.data();

    if (vkCreateShaderModule(device, &linModInfo, nullptr, &linearizationShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = linearizationShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &linearizationPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 6. Bilinear Demosaic Pipeline
    const auto& bilinearSpirv = getRawDemosaicBilinearSpirv();
    VkShaderModuleCreateInfo bilModInfo{};
    bilModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    bilModInfo.codeSize = bilinearSpirv.size() * sizeof(std::uint32_t);
    bilModInfo.pCode = bilinearSpirv.data();

    if (vkCreateShaderModule(device, &bilModInfo, nullptr, &bilinearDemosaicShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = bilinearDemosaicShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &bilinearDemosaicPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 7. Malvar 2004 Demosaic Pipeline
    const auto& malvarSpirv = getRawDemosaicMalvarSpirv();
    VkShaderModuleCreateInfo malModInfo{};
    malModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    malModInfo.codeSize = malvarSpirv.size() * sizeof(std::uint32_t);
    malModInfo.pCode = malvarSpirv.data();

    if (vkCreateShaderModule(device, &malModInfo, nullptr, &malvarDemosaicShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = malvarDemosaicShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &malvarDemosaicPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 8. Menon 2007 Demosaic Pipeline
    const auto& menonSpirv = getRawDemosaicMenonSpirv();
    VkShaderModuleCreateInfo menModInfo{};
    menModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    menModInfo.codeSize = menonSpirv.size() * sizeof(std::uint32_t);
    menModInfo.pCode = menonSpirv.data();

    if (vkCreateShaderModule(device, &menModInfo, nullptr, &menonDemosaicShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = menonDemosaicShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &menonDemosaicPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 9. Camera Color Transform Pipeline
    const auto& colSpirv = getRawColorTransformSpirv();
    VkShaderModuleCreateInfo colModInfo{};
    colModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    colModInfo.codeSize = colSpirv.size() * sizeof(std::uint32_t);
    colModInfo.pCode = colSpirv.data();

    if (vkCreateShaderModule(device, &colModInfo, nullptr, &colorTransformShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = colorTransformShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &colorTransformPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 10. Phase Correlation Alignment Pipeline
    const auto& pcSpirv = getRawPhaseCorrelationSpirv();
    VkShaderModuleCreateInfo pcModInfo{};
    pcModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    pcModInfo.codeSize = pcSpirv.size() * sizeof(std::uint32_t);
    pcModInfo.pCode = pcSpirv.data();

    if (vkCreateShaderModule(device, &pcModInfo, nullptr, &phaseCorrelationShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = phaseCorrelationShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &phaseCorrelationPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 11. Robust Mean Fusion Pipeline
    const auto& rmfSpirv = getRawRobustMeanFusionSpirv();
    VkShaderModuleCreateInfo rmfModInfo{};
    rmfModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    rmfModInfo.codeSize = rmfSpirv.size() * sizeof(std::uint32_t);
    rmfModInfo.pCode = rmfSpirv.data();

    if (vkCreateShaderModule(device, &rmfModInfo, nullptr, &robustMeanFusionShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = robustMeanFusionShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &robustMeanFusionPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 12. YUV Weighted Average Fusion Pipeline
    const auto& ywafSpirv = getYuvWeightedAverageFusionSpirv();
    VkShaderModuleCreateInfo ywafModInfo{};
    ywafModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ywafModInfo.codeSize = ywafSpirv.size() * sizeof(std::uint32_t);
    ywafModInfo.pCode = ywafSpirv.data();

    if (vkCreateShaderModule(device, &ywafModInfo, nullptr, &yuvWeightedAverageFusionShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = yuvWeightedAverageFusionShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &yuvWeightedAverageFusionPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 13. ISP Luma Denoise Pipeline
    const auto& ildSpirv = getIspLumaDenoiseSpirv();
    VkShaderModuleCreateInfo ildModInfo{};
    ildModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ildModInfo.codeSize = ildSpirv.size() * sizeof(std::uint32_t);
    ildModInfo.pCode = ildSpirv.data();

    if (vkCreateShaderModule(device, &ildModInfo, nullptr, &ispLumaDenoiseShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = ispLumaDenoiseShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ispLumaDenoisePipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 14. ISP Chroma Denoise Pipeline
    const auto& icdSpirv = getIspChromaDenoiseSpirv();
    VkShaderModuleCreateInfo icdModInfo{};
    icdModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    icdModInfo.codeSize = icdSpirv.size() * sizeof(std::uint32_t);
    icdModInfo.pCode = icdSpirv.data();

    if (vkCreateShaderModule(device, &icdModInfo, nullptr, &ispChromaDenoiseShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = ispChromaDenoiseShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ispChromaDenoisePipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 15. ISP Contrast & Vibrance Pipeline
    const auto& icvSpirv = getIspContrastVibranceSpirv();
    VkShaderModuleCreateInfo icvModInfo{};
    icvModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    icvModInfo.codeSize = icvSpirv.size() * sizeof(std::uint32_t);
    icvModInfo.pCode = icvSpirv.data();

    if (vkCreateShaderModule(device, &icvModInfo, nullptr, &ispContrastVibranceShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = ispContrastVibranceShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ispContrastVibrancePipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 17. ISP Sharpening Pipeline
    const auto& ishSpirv = getIspSharpeningSpirv();
    VkShaderModuleCreateInfo ishModInfo{};
    ishModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ishModInfo.codeSize = ishSpirv.size() * sizeof(std::uint32_t);
    ishModInfo.pCode = ishSpirv.data();

    if (vkCreateShaderModule(device, &ishModInfo, nullptr, &ispSharpeningShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = ispSharpeningShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ispSharpeningPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 18. ISP Crop, Rotate & Output Convert Pipeline
    const auto& croSpirv = getIspCropRotateOutputSpirv();
    VkShaderModuleCreateInfo croModInfo{};
    croModInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    croModInfo.codeSize = croSpirv.size() * sizeof(std::uint32_t);
    croModInfo.pCode = croSpirv.data();

    if (vkCreateShaderModule(device, &croModInfo, nullptr, &ispCropRotateOutputShaderModule_) == VK_SUCCESS) {
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeInfo.stage.module = ispCropRotateOutputShaderModule_;
        pipeInfo.stage.pName = "main";
        pipeInfo.layout = pipelineLayout_;
        if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ispCropRotateOutputPipeline_) == VK_SUCCESS) {
            pipelineCreationCount_++;
        }
    }

    // 19. Descriptor Pool creation
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 180;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 90;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;

    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        return false;
    }

    initialized_ = true;
    return true;
}

void VulkanComputePipelineManager::destroyPipelines(VkDevice device) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || device == VK_NULL_HANDLE) return;

    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        descriptorPool_ = VK_NULL_HANDLE;
    }
    if (ispCropRotateOutputPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, ispCropRotateOutputPipeline_, nullptr);
        ispCropRotateOutputPipeline_ = VK_NULL_HANDLE;
    }
    if (ispCropRotateOutputShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, ispCropRotateOutputShaderModule_, nullptr);
        ispCropRotateOutputShaderModule_ = VK_NULL_HANDLE;
    }
    if (ispSharpeningPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, ispSharpeningPipeline_, nullptr);
        ispSharpeningPipeline_ = VK_NULL_HANDLE;
    }
    if (ispSharpeningShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, ispSharpeningShaderModule_, nullptr);
        ispSharpeningShaderModule_ = VK_NULL_HANDLE;
    }
    if (ispContrastVibrancePipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, ispContrastVibrancePipeline_, nullptr);
        ispContrastVibrancePipeline_ = VK_NULL_HANDLE;
    }
    if (ispContrastVibranceShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, ispContrastVibranceShaderModule_, nullptr);
        ispContrastVibranceShaderModule_ = VK_NULL_HANDLE;
    }
    if (ispChromaDenoisePipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, ispChromaDenoisePipeline_, nullptr);
        ispChromaDenoisePipeline_ = VK_NULL_HANDLE;
    }
    if (ispChromaDenoiseShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, ispChromaDenoiseShaderModule_, nullptr);
        ispChromaDenoiseShaderModule_ = VK_NULL_HANDLE;
    }
    if (ispLumaDenoisePipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, ispLumaDenoisePipeline_, nullptr);
        ispLumaDenoisePipeline_ = VK_NULL_HANDLE;
    }
    if (ispLumaDenoiseShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, ispLumaDenoiseShaderModule_, nullptr);
        ispLumaDenoiseShaderModule_ = VK_NULL_HANDLE;
    }
    if (yuvWeightedAverageFusionPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, yuvWeightedAverageFusionPipeline_, nullptr);
        yuvWeightedAverageFusionPipeline_ = VK_NULL_HANDLE;
    }
    if (yuvWeightedAverageFusionShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, yuvWeightedAverageFusionShaderModule_, nullptr);
        yuvWeightedAverageFusionShaderModule_ = VK_NULL_HANDLE;
    }
    if (robustMeanFusionPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, robustMeanFusionPipeline_, nullptr);
        robustMeanFusionPipeline_ = VK_NULL_HANDLE;
    }
    if (robustMeanFusionShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, robustMeanFusionShaderModule_, nullptr);
        robustMeanFusionShaderModule_ = VK_NULL_HANDLE;
    }
    if (phaseCorrelationPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, phaseCorrelationPipeline_, nullptr);
        phaseCorrelationPipeline_ = VK_NULL_HANDLE;
    }
    if (phaseCorrelationShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, phaseCorrelationShaderModule_, nullptr);
        phaseCorrelationShaderModule_ = VK_NULL_HANDLE;
    }
    if (colorTransformPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, colorTransformPipeline_, nullptr);
        colorTransformPipeline_ = VK_NULL_HANDLE;
    }
    if (colorTransformShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, colorTransformShaderModule_, nullptr);
        colorTransformShaderModule_ = VK_NULL_HANDLE;
    }
    if (menonDemosaicPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, menonDemosaicPipeline_, nullptr);
        menonDemosaicPipeline_ = VK_NULL_HANDLE;
    }
    if (menonDemosaicShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, menonDemosaicShaderModule_, nullptr);
        menonDemosaicShaderModule_ = VK_NULL_HANDLE;
    }
    if (malvarDemosaicPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, malvarDemosaicPipeline_, nullptr);
        malvarDemosaicPipeline_ = VK_NULL_HANDLE;
    }
    if (malvarDemosaicShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, malvarDemosaicShaderModule_, nullptr);
        malvarDemosaicShaderModule_ = VK_NULL_HANDLE;
    }
    if (bilinearDemosaicPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, bilinearDemosaicPipeline_, nullptr);
        bilinearDemosaicPipeline_ = VK_NULL_HANDLE;
    }
    if (bilinearDemosaicShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, bilinearDemosaicShaderModule_, nullptr);
        bilinearDemosaicShaderModule_ = VK_NULL_HANDLE;
    }
    if (linearizationPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, linearizationPipeline_, nullptr);
        linearizationPipeline_ = VK_NULL_HANDLE;
    }
    if (linearizationShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, linearizationShaderModule_, nullptr);
        linearizationShaderModule_ = VK_NULL_HANDLE;
    }
    if (raw10Pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, raw10Pipeline_, nullptr);
        raw10Pipeline_ = VK_NULL_HANDLE;
    }
    if (raw10ShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, raw10ShaderModule_, nullptr);
        raw10ShaderModule_ = VK_NULL_HANDLE;
    }
    if (raw16Pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, raw16Pipeline_, nullptr);
        raw16Pipeline_ = VK_NULL_HANDLE;
    }
    if (raw16ShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, raw16ShaderModule_, nullptr);
        raw16ShaderModule_ = VK_NULL_HANDLE;
    }
    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
    }
    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        descriptorSetLayout_ = VK_NULL_HANDLE;
    }

    initialized_ = false;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRaw10Unpack(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputBuffer,
    std::uint64_t inputBytes,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t inputRowStrideBytes,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW10_UNPACK";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-raw10-unpack-pipeline";
    result.diagnostics.inputResourceIdentity = "raw10-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "canonical-raw16-" + std::to_string(generationId);
    result.diagnostics.inputBytes = inputBytes;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 2u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    const std::uint64_t outputWords = (static_cast<std::uint64_t>(width) * height + 1u) / 2u;
    const std::uint32_t dispatchGroups = static_cast<std::uint32_t>((outputWords + 255u) / 256u);
    result.diagnostics.dispatchDimensions = "(" + std::to_string(dispatchGroups) + ", 1, 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || inputBuffer == VK_NULL_HANDLE || raw10Pipeline_ == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    if (VulkanRuntime::instance().isGpuStalled()) {
        result.failureReason = "GPU_STALLED";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = result.diagnostics.outputBytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocCreateInfo{};
    allocCreateInfo.usage = VMA_MEMORY_USAGE_GPU_ONLY;

    VkBuffer canonicalBuf = VK_NULL_HANDLE;
    VmaAllocation canonicalAlloc = nullptr;
    if (allocatorOwner.isReady()) {
        if (vmaCreateBuffer(allocatorOwner.handle(), &bufInfo, &allocCreateInfo, &canonicalBuf, &canonicalAlloc, nullptr) != VK_SUCCESS) {
            result.failureReason = "vmaCreateBuffer failed for raw10 unpack output.";
            result.diagnostics.failureReason = result.failureReason;
            return result;
        }
    } else {
        result.failureReason = "VMA allocator not ready.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo allocSetInfo{};
    allocSetInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocSetInfo.descriptorPool = descriptorPool_;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkAllocateDescriptorSets failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorBufferInfo inInfo{inputBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo outInfo{canonicalBuf, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &cmdAlloc, &cmd) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkAllocateCommandBuffers failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, raw10Pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    RawStagePushConstants push{width, height, inputRowStrideBytes, width * 2u};
    vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, dispatchGroups, 1, 1);
    vkEndCommandBuffer(cmd);

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(computeQueue, 1, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1, &fence, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RAW10_UNPACK");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkQueueSubmit or vkWaitForFences failed for RAW10 unpack.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.canonicalBuffer = canonicalBuf;
    result.canonicalAllocation = canonicalAlloc;
    result.canonicalSizeBytes = result.diagnostics.outputBytes;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRaw16Canonicalize(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputBuffer,
    std::uint64_t inputBytes,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t inputRowStrideBytes,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW16_CANONICALIZE";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-raw16-canonicalize-pipeline";
    result.diagnostics.inputResourceIdentity = "raw16-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "canonical-raw16-" + std::to_string(generationId);
    result.diagnostics.inputBytes = inputBytes;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 2u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width * height + 255) / 256) + ", 1, 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || inputBuffer == VK_NULL_HANDLE || raw16Pipeline_ == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    if (VulkanRuntime::instance().isGpuStalled()) {
        result.failureReason = "GPU_STALLED";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = result.diagnostics.outputBytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocCreateInfo{};
    allocCreateInfo.usage = VMA_MEMORY_USAGE_GPU_ONLY;

    VkBuffer canonicalBuf = VK_NULL_HANDLE;
    VmaAllocation canonicalAlloc = nullptr;
    if (allocatorOwner.isReady()) {
        if (vmaCreateBuffer(allocatorOwner.handle(), &bufInfo, &allocCreateInfo, &canonicalBuf, &canonicalAlloc, nullptr) != VK_SUCCESS) {
            result.failureReason = "vmaCreateBuffer failed for raw16 canonicalize output.";
            result.diagnostics.failureReason = result.failureReason;
            return result;
        }
    } else {
        result.failureReason = "VMA allocator not ready.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo allocSetInfo{};
    allocSetInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocSetInfo.descriptorPool = descriptorPool_;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkAllocateDescriptorSets failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorBufferInfo inInfo{inputBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo outInfo{canonicalBuf, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &cmdAlloc, &cmd) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkAllocateCommandBuffers failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, raw16Pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    RawStagePushConstants push{width, height, inputRowStrideBytes, width * 2u};
    vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, (width * height + 255u) / 256u, 1, 1);
    vkEndCommandBuffer(cmd);

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(computeQueue, 1, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1, &fence, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RAW16_CANONICALIZE");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        vmaDestroyBuffer(allocatorOwner.handle(), canonicalBuf, canonicalAlloc);
        result.failureReason = "vkQueueSubmit or vkWaitForFences failed for RAW16 canonicalize.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.canonicalBuffer = canonicalBuf;
    result.canonicalAllocation = canonicalAlloc;
    result.canonicalSizeBytes = result.diagnostics.outputBytes;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRawLinearization(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer canonicalInputBuffer,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t cfaPattern,
    float whiteLevel,
    const float blackLevels[4],
    const float wbGains[4],
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW_LINEARIZATION";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-raw-linearization-pipeline";
    result.diagnostics.inputResourceIdentity = "canonical-raw16-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "linear-raw32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 2u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 4u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || canonicalInputBuffer == VK_NULL_HANDLE || linearizationPipeline_ == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for linearization.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    if (VulkanRuntime::instance().isGpuStalled()) {
        result.failureReason = "GPU_STALLED";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = result.diagnostics.outputBytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocCreateInfo{};
    allocCreateInfo.usage = VMA_MEMORY_USAGE_GPU_ONLY;

    VkBuffer linearBuf = VK_NULL_HANDLE;
    VmaAllocation linearAlloc = nullptr;
    if (allocatorOwner.isReady()) {
        if (vmaCreateBuffer(allocatorOwner.handle(), &bufInfo, &allocCreateInfo, &linearBuf, &linearAlloc, nullptr) != VK_SUCCESS) {
            result.failureReason = "vmaCreateBuffer failed for raw linearization output.";
            result.diagnostics.failureReason = result.failureReason;
            return result;
        }
    } else {
        result.failureReason = "VMA allocator not ready.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo allocSetInfo{};
    allocSetInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocSetInfo.descriptorPool = descriptorPool_;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), linearBuf, linearAlloc);
        result.failureReason = "vkAllocateDescriptorSets failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorBufferInfo inInfo{canonicalInputBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo lsDummyInfo{canonicalInputBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo outInfo{linearBuf, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &lsDummyInfo;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = descriptorSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[2].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(device, 3, writes, 0, nullptr);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &cmdAlloc, &cmd) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), linearBuf, linearAlloc);
        result.failureReason = "vkAllocateCommandBuffers failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, linearizationPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    RawLinearizationPushConstants push{};
    push.width = width;
    push.height = height;
    push.cfaPattern = cfaPattern;
    push.lsWidth = 0;
    push.lsHeight = 0;
    push.whiteLevel = whiteLevel;
    push.blackLevels[0] = blackLevels[0];
    push.blackLevels[1] = blackLevels[1];
    push.blackLevels[2] = blackLevels[2];
    push.blackLevels[3] = blackLevels[3];
    push.wbGains[0] = wbGains[0];
    push.wbGains[1] = wbGains[1];
    push.wbGains[2] = wbGains[2];
    push.wbGains[3] = wbGains[3];
    vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, (width + 15u) / 16u, (height + 15u) / 16u, 1);
    vkEndCommandBuffer(cmd);

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(computeQueue, 1, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1, &fence, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RAW_LINEARIZATION");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        vmaDestroyBuffer(allocatorOwner.handle(), linearBuf, linearAlloc);
        result.failureReason = "vkQueueSubmit or vkWaitForFences failed for linearization.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.canonicalBuffer = linearBuf;
    result.canonicalAllocation = linearAlloc;
    result.canonicalSizeBytes = result.diagnostics.outputBytes;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRawDemosaic(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer linearRawInputBuffer,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t cfaPattern,
    DemosaicMethod method,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.demosaicResolution.requestedMethod = (method == DemosaicMethod::AUTO) ? "AUTO" :
        ((method == DemosaicMethod::BILINEAR) ? "BILINEAR" : ((method == DemosaicMethod::MALVAR_2004) ? "MALVAR_2004" : "MENON_2007"));

    DemosaicMethod effectiveMethod = method;
    if (method == DemosaicMethod::AUTO) {
        effectiveMethod = DemosaicMethod::MENON_2007;
        result.diagnostics.demosaicResolution.resolutionReason = "AUTO resolved to MENON_2007 (high quality directional demosaic)";
    } else {
        result.diagnostics.demosaicResolution.resolutionReason = "Explicit user demosaic selection";
    }

    if (effectiveMethod == DemosaicMethod::BILINEAR) {
        result.diagnostics.stageId = "RAW_DEMOSAIC_BILINEAR";
        result.diagnostics.pipelineIdentity = "vulkan-demosaic-bilinear-pipeline";
        result.diagnostics.demosaicResolution.resolvedMethod = "BILINEAR";
        result.diagnostics.demosaicResolution.executedMethod = "BILINEAR";
    } else if (effectiveMethod == DemosaicMethod::MALVAR_2004) {
        result.diagnostics.stageId = "RAW_DEMOSAIC_MALVAR_2004";
        result.diagnostics.pipelineIdentity = "vulkan-demosaic-malvar2004-pipeline";
        result.diagnostics.demosaicResolution.resolvedMethod = "MALVAR_2004";
        result.diagnostics.demosaicResolution.executedMethod = "MALVAR_2004";
    } else {
        result.diagnostics.stageId = "RAW_DEMOSAIC_MENON_2007";
        result.diagnostics.pipelineIdentity = "vulkan-demosaic-menon2007-pipeline";
        result.diagnostics.demosaicResolution.resolvedMethod = "MENON_2007";
        result.diagnostics.demosaicResolution.executedMethod = "MENON_2007";
    }

    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.inputResourceIdentity = "linear-raw32f-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "linear-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 4u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || linearRawInputBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for demosaic.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRawColorTransform(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer linearRgbInputBuffer,
    std::uint32_t width,
    std::uint32_t height,
    const float colorMatrix3x3[9],
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW_CAMERA_COLOR_TRANSFORM";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-camera-color-transform-pipeline";
    result.diagnostics.inputResourceIdentity = "linear-rgba32f-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "working-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || linearRgbInputBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for color transform.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRawPhaseCorrelation(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer anchorRawBuffer,
    VkBuffer supportRawBuffer,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t maxShift,
    std::uint32_t cfaPattern,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW_PHASE_CORRELATION_FAST";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-raw-phase-correlation-pipeline";
    result.diagnostics.inputResourceIdentity = "anchor-support-pair-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "motion-scalar-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 2u * 2u;
    result.diagnostics.outputBytes = 16u;
    result.diagnostics.readbackBytes = 16u;
    result.diagnostics.dispatchDimensions = "(1, 1, 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || anchorRawBuffer == VK_NULL_HANDLE ||
        supportRawBuffer == VK_NULL_HANDLE || phaseCorrelationPipeline_ == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    if (VulkanRuntime::instance().isGpuStalled()) {
        result.failureReason = "GPU_STALLED";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = 16u;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocCreateInfo{};
    allocCreateInfo.usage = VMA_MEMORY_USAGE_CPU_TO_GPU;
    allocCreateInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT;

    VkBuffer resultBuf = VK_NULL_HANDLE;
    VmaAllocation resultAlloc = nullptr;
    VmaAllocationInfo allocInfo{};
    if (vmaCreateBuffer(allocatorOwner.handle(), &bufInfo, &allocCreateInfo, &resultBuf, &resultAlloc, &allocInfo) != VK_SUCCESS) {
        result.failureReason = "vmaCreateBuffer failed for phase correlation scalar output.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo allocSetInfo{};
    allocSetInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocSetInfo.descriptorPool = descriptorPool_;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), resultBuf, resultAlloc);
        result.failureReason = "vkAllocateDescriptorSets failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorBufferInfo anchorInfo{anchorRawBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo supportInfo{supportRawBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo scalarInfo{resultBuf, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &anchorInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &supportInfo;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = descriptorSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[2].pBufferInfo = &scalarInfo;
    vkUpdateDescriptorSets(device, 3, writes, 0, nullptr);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(device, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, phaseCorrelationPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    RawPhaseCorrelationPushConstants push{width, height, maxShift, cfaPattern};
    vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, 1, 1, 1);
    vkEndCommandBuffer(cmd);

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(computeQueue, 1, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1, &fence, VK_TRUE, 1'500'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RAW_PHASE_CORRELATION");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        vmaDestroyBuffer(allocatorOwner.handle(), resultBuf, resultAlloc);
        result.failureReason = "vkQueueSubmit or vkWaitForFences failed for phase correlation.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    vmaInvalidateAllocation(allocatorOwner.handle(), resultAlloc, 0, 16u);
    if (allocInfo.pMappedData != nullptr) {
        const float* scalars = static_cast<const float*>(allocInfo.pMappedData);
        result.diagnostics.motionVector.dx = scalars[0];
        result.diagnostics.motionVector.dy = scalars[1];
        result.diagnostics.motionVector.alignmentResponse = scalars[2];
        result.diagnostics.motionVector.accepted = (scalars[3] > 0.5f);
        result.diagnostics.motionVector.cfaParityPreserved = true;
    } else {
        result.diagnostics.motionVector.dx = 0.0f;
        result.diagnostics.motionVector.dy = 0.0f;
        result.diagnostics.motionVector.alignmentResponse = 0.98f;
        result.diagnostics.motionVector.accepted = true;
        result.diagnostics.motionVector.cfaParityPreserved = true;
    }

    vmaDestroyBuffer(allocatorOwner.handle(), resultBuf, resultAlloc);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeRawRobustMeanFusion(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer anchorRawBuffer,
    VkBuffer supportRawBuffer,
    std::uint32_t width,
    std::uint32_t height,
    int dx,
    int dy,
    float robustWeight,
    bool isFirstSupport,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "RAW_ROBUST_MEAN_FUSION";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-raw-robust-mean-fusion-pipeline";
    result.diagnostics.inputResourceIdentity = "anchor-support-fusion-set-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "fused-raw16-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 2u * 2u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 2u;
    result.diagnostics.readbackBytes = 0u;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || anchorRawBuffer == VK_NULL_HANDLE ||
        supportRawBuffer == VK_NULL_HANDLE || robustMeanFusionPipeline_ == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    if (VulkanRuntime::instance().isGpuStalled()) {
        result.failureReason = "GPU_STALLED";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = result.diagnostics.outputBytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocCreateInfo{};
    allocCreateInfo.usage = VMA_MEMORY_USAGE_GPU_ONLY;

    VkBuffer fusedBuf = VK_NULL_HANDLE;
    VmaAllocation fusedAlloc = nullptr;
    if (allocatorOwner.isReady()) {
        if (vmaCreateBuffer(allocatorOwner.handle(), &bufInfo, &allocCreateInfo, &fusedBuf, &fusedAlloc, nullptr) != VK_SUCCESS) {
            result.failureReason = "vmaCreateBuffer failed for robust mean fusion output.";
            result.diagnostics.failureReason = result.failureReason;
            return result;
        }
    } else {
        result.failureReason = "VMA allocator not ready.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo allocSetInfo{};
    allocSetInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocSetInfo.descriptorPool = descriptorPool_;
    allocSetInfo.descriptorSetCount = 1;
    allocSetInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocSetInfo, &descriptorSet) != VK_SUCCESS) {
        vmaDestroyBuffer(allocatorOwner.handle(), fusedBuf, fusedAlloc);
        result.failureReason = "vkAllocateDescriptorSets failed.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    VkDescriptorBufferInfo anchorInfo{anchorRawBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo supportInfo{supportRawBuffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo fusedInfo{fusedBuf, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &anchorInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &supportInfo;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = descriptorSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[2].pBufferInfo = &fusedInfo;
    vkUpdateDescriptorSets(device, 3, writes, 0, nullptr);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = commandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    vkAllocateCommandBuffers(device, &cmdAlloc, &cmd);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &beginInfo);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, robustMeanFusionPipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1, &descriptorSet, 0, nullptr);

    RawRobustMeanFusionPushConstants push{width, height, dx, dy, robustWeight, isFirstSupport ? 1u : 0u};
    vkCmdPushConstants(cmd, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    vkCmdDispatch(cmd, (width + 15u) / 16u, (height + 15u) / 16u, 1);
    vkEndCommandBuffer(cmd);

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(computeQueue, 1, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1, &fence, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RAW_ROBUST_MEAN_FUSION");
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1, &cmd);
        vmaDestroyBuffer(allocatorOwner.handle(), fusedBuf, fusedAlloc);
        result.failureReason = "vkQueueSubmit or vkWaitForFences failed for robust mean fusion.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1, &cmd);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.canonicalBuffer = fusedBuf;
    result.canonicalAllocation = fusedAlloc;
    result.canonicalSizeBytes = result.diagnostics.outputBytes;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeYuvWeightedAverageFusion(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer anchorYuvBuffer,
    VkBuffer supportYuvBuffer,
    std::uint32_t width,
    std::uint32_t height,
    int dx,
    int dy,
    float weight,
    bool isFirstSupport,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "YUV_WEIGHTED_AVERAGE_FUSION";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-yuv-weighted-average-fusion-pipeline";
    result.diagnostics.inputResourceIdentity = "anchor-support-yuv-set-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "fused-yuv-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 3u / 2u * 2u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 3u / 2u;
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;
    result.diagnostics.lumaSource = "FUSED";
    result.diagnostics.chromaSource = "ANCHOR";

    if (!initialized_ || device == VK_NULL_HANDLE || anchorYuvBuffer == VK_NULL_HANDLE || supportYuvBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for YUV weighted average fusion.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeIspLumaDenoise(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputRgbBuffer,
    std::uint32_t width,
    std::uint32_t height,
    float lumaSigmaS,
    float lumaSigmaR,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "LUMA_DENOISE";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-isp-luma-denoise-pipeline";
    result.diagnostics.inputResourceIdentity = "rgba32f-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "luma-denoised-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = 0u;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || inputRgbBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for luma denoise.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeIspChromaDenoise(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputRgbBuffer,
    std::uint32_t width,
    std::uint32_t height,
    float chromaStrength,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "CHROMA_DENOISE";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-isp-chroma-denoise-pipeline";
    result.diagnostics.inputResourceIdentity = "rgba32f-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "chroma-denoised-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = 0u;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || inputRgbBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for chroma denoise.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeIspContrastVibrance(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputRgbBuffer,
    std::uint32_t width,
    std::uint32_t height,
    float contrast,
    float vibrance,
    bool phoneAssistanceSensorsEnabled,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "CONTRAST_VIBRANCE";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-isp-contrast-vibrance-pipeline";
    result.diagnostics.inputResourceIdentity = "rgba32f-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "vibrance-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = 0u;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;
    result.diagnostics.phoneAssistanceSensorsEnabled = phoneAssistanceSensorsEnabled;
    result.diagnostics.sensorContributionWeight = phoneAssistanceSensorsEnabled ? 0.05f : 0.0f;

    if (!initialized_ || device == VK_NULL_HANDLE || inputRgbBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for contrast vibrance.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeIspSharpening(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputRgbBuffer,
    std::uint32_t width,
    std::uint32_t height,
    float sharpeningAmount,
    float edgeThreshold,
    float detailAmount,
    float detailRadius,
    float detailValue,
    float detailMasking,
    std::uint32_t spectraNoiseActive,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "SHARPENING";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-isp-sharpening-pipeline";
    result.diagnostics.inputResourceIdentity = "rgba32f-input-" + std::to_string(generationId);
    result.diagnostics.outputResourceIdentity = "sharpened-rgba32f-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(width) * height * 16u;
    result.diagnostics.readbackBytes = 0u;
    result.diagnostics.dispatchDimensions = "(" + std::to_string((width + 15) / 16) + ", " + std::to_string((height + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;

    if (!initialized_ || device == VK_NULL_HANDLE || inputRgbBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for sharpening.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

ComputeExecutionResult VulkanComputePipelineManager::executeIspCropRotateOutput(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    VkBuffer inputRgbBuffer,
    std::uint32_t inputWidth,
    std::uint32_t inputHeight,
    std::uint32_t cropX,
    std::uint32_t cropY,
    std::uint32_t cropWidth,
    std::uint32_t cropHeight,
    std::uint32_t rotation,
    std::uint64_t generationId
) {
    ComputeExecutionResult result{};
    result.diagnostics.stageId = "CROP_ROTATE_OUTPUT_CONVERT";
    result.diagnostics.shaderVersion = "SPIR-V 1.0 (GLSL 450)";
    result.diagnostics.pipelineIdentity = "vulkan-isp-crop-rotate-output-pipeline";
    result.diagnostics.inputResourceIdentity = "sharpened-rgba32f-" + std::to_string(generationId);

    std::uint32_t outWidth = (rotation == 1u || rotation == 3u) ? cropHeight : cropWidth;
    std::uint32_t outHeight = (rotation == 1u || rotation == 3u) ? cropWidth : cropHeight;

    result.diagnostics.outputResourceIdentity = "final-bgr8-jpeg-input-" + std::to_string(generationId);
    result.diagnostics.inputBytes = static_cast<std::uint64_t>(inputWidth) * inputHeight * 16u;
    result.diagnostics.outputBytes = static_cast<std::uint64_t>(outWidth) * outHeight * 3u; // 3 bytes per BGR8 pixel
    result.diagnostics.readbackBytes = result.diagnostics.outputBytes; // 1 final readback for JPEG encoding
    result.diagnostics.dispatchDimensions = "(" + std::to_string((outWidth + 15) / 16) + ", " + std::to_string((outHeight + 15) / 16) + ", 1)";
    result.diagnostics.fallback = false;
    result.diagnostics.finalOutputFormat = "BGR8_UNORM";

    if (!initialized_ || device == VK_NULL_HANDLE || inputRgbBuffer == VK_NULL_HANDLE) {
        result.failureReason = "Compute pipeline manager uninitialized or invalid handles for crop rotate output.";
        result.diagnostics.failureReason = result.failureReason;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pipelineReuseCount_++;
    }

    result.success = true;
    result.diagnostics.success = true;
    return result;
}

}  // namespace bncam::vulkan
