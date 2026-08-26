#pragma once

#include "VulkanVmaForward.h"
#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>

namespace bncam::vulkan {

struct VmaCreateRequest {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::uint32_t vulkanApiVersion = VK_API_VERSION_1_1;
};

/**
 * Exact single-owner integration point for VMA. External AHardwareBuffer memory is intentionally
 * not owned by this allocator; imported memory follows dedicated Android/Vulkan binding rules.
 */
class VulkanAllocatorOwner final {
public:
    VulkanAllocatorOwner() = default;
    ~VulkanAllocatorOwner();

    VulkanAllocatorOwner(const VulkanAllocatorOwner&) = delete;
    VulkanAllocatorOwner& operator=(const VulkanAllocatorOwner&) = delete;
    VulkanAllocatorOwner(VulkanAllocatorOwner&& other) noexcept;
    VulkanAllocatorOwner& operator=(VulkanAllocatorOwner&& other) noexcept;

    bool create(const VmaCreateRequest& request, std::string& failureReason) noexcept;
    void destroy() noexcept;
    bool isReady() const noexcept { return allocator_ != nullptr; }
    VmaAllocator handle() const noexcept { return allocator_; }
    std::string boundedStatisticsJson() const;
    static bool headerAvailableAtBuildTime() noexcept;
    static const char* pinnedVersion() noexcept { return "3.3.0"; }

private:
    VmaAllocator allocator_ = nullptr;
};

}  // namespace bncam::vulkan
