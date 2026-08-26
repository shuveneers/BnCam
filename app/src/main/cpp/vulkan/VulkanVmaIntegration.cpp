#include "VulkanVmaIntegration.h"
#include "VulkanJson.h"

#include <sstream>

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif

#if BNCAM_VMA_HEADER_AVAILABLE
#define VMA_IMPLEMENTATION
#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#include "vk_mem_alloc.h"
#endif

namespace bncam::vulkan {

VulkanAllocatorOwner::~VulkanAllocatorOwner() {
    destroy();
}

VulkanAllocatorOwner::VulkanAllocatorOwner(VulkanAllocatorOwner&& other) noexcept
    : allocator_(other.allocator_) {
    other.allocator_ = nullptr;
}

VulkanAllocatorOwner& VulkanAllocatorOwner::operator=(VulkanAllocatorOwner&& other) noexcept {
    if (this == &other) return *this;
    destroy();
    allocator_ = other.allocator_;
    other.allocator_ = nullptr;
    return *this;
}

bool VulkanAllocatorOwner::headerAvailableAtBuildTime() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    return true;
#else
    return false;
#endif
}

bool VulkanAllocatorOwner::create(
    const VmaCreateRequest& request,
    std::string& failureReason
) noexcept {
    if (allocator_ != nullptr) return true;
    if (request.instance == VK_NULL_HANDLE ||
        request.physicalDevice == VK_NULL_HANDLE ||
        request.device == VK_NULL_HANDLE) {
        failureReason = "vma_create_rejected_missing_vulkan_handles";
        return false;
    }
#if BNCAM_VMA_HEADER_AVAILABLE
    VmaVulkanFunctions functions{};
    functions.vkGetInstanceProcAddr = vkGetInstanceProcAddr;
    functions.vkGetDeviceProcAddr = vkGetDeviceProcAddr;

    VmaAllocatorCreateInfo createInfo{};
    createInfo.instance = request.instance;
    createInfo.physicalDevice = request.physicalDevice;
    createInfo.device = request.device;
    createInfo.vulkanApiVersion = request.vulkanApiVersion;
    createInfo.pVulkanFunctions = &functions;

    const VkResult result = vmaCreateAllocator(&createInfo, &allocator_);
    if (result != VK_SUCCESS) {
        allocator_ = nullptr;
        failureReason = "vmaCreateAllocator_failed_vk_result_" + std::to_string(result);
        return false;
    }
    failureReason.clear();
    return true;
#else
    (void) request;
    failureReason = "vma_header_missing_run_app_tools_fetch_vma";
    return false;
#endif
}

void VulkanAllocatorOwner::destroy() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        vmaDestroyAllocator(allocator_);
        allocator_ = nullptr;
    }
#else
    allocator_ = nullptr;
#endif
}

std::string VulkanAllocatorOwner::boundedStatisticsJson() const {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ == nullptr) {
        return R"({"available":true,"ready":false,"version":"3.3.0"})";
    }
    VmaTotalStatistics stats{};
    vmaCalculateStatistics(allocator_, &stats);
    std::ostringstream out;
    out << '{'
        << "\"available\":true,"
        << "\"ready\":true,"
        << "\"version\":\"3.3.0\","
        << "\"blockCount\":" << stats.total.statistics.blockCount << ','
        << "\"allocationCount\":" << stats.total.statistics.allocationCount << ','
        << "\"blockBytes\":" << stats.total.statistics.blockBytes << ','
        << "\"allocationBytes\":" << stats.total.statistics.allocationBytes
        << '}';
    return out.str();
#else
    return R"({"available":false,"ready":false,"version":"3.3.0","reason":"header_missing"})";
#endif
}

}  // namespace bncam::vulkan
