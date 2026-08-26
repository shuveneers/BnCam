#pragma once

#include "VulkanValidationCollector.h"
#include <vulkan/vulkan.h>

namespace bncam::vulkan {

/** Ready-to-register VK_EXT_debug_utils callback. It only copies bounded data into the collector. */
VKAPI_ATTR VkBool32 VKAPI_CALL debugUtilsCallback(
    VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
    VkDebugUtilsMessageTypeFlagsEXT messageTypes,
    const VkDebugUtilsMessengerCallbackDataEXT* callbackData,
    void* userData
) noexcept;

}  // namespace bncam::vulkan
