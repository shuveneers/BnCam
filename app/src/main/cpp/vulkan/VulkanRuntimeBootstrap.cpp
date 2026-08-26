#include "VulkanRuntimeBootstrap.h"
#include "VulkanRuntime.h"
#include "VulkanDebugUtilsAdapter.h"
#include "VulkanVmaIntegration.h"
#include "VulkanPipelineCacheRegistry.h"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstring>
#include <exception>
#include <fstream>
#include <set>
#include <string>
#include <vector>

namespace bncam::vulkan {

namespace {

bool isExtensionSupported(
    const char* extensionName,
    const std::vector<VkExtensionProperties>& availableExtensions
) {
    if (extensionName == nullptr) return false;
    for (const auto& ext : availableExtensions) {
        if (std::strcmp(ext.extensionName, extensionName) == 0) {
            return true;
        }
    }
    return false;
}

bool isLayerSupported(
    const char* layerName,
    const std::vector<VkLayerProperties>& availableLayers
) {
    if (layerName == nullptr) return false;
    for (const auto& layer : availableLayers) {
        if (std::strcmp(layer.layerName, layerName) == 0) {
            return true;
        }
    }
    return false;
}


std::vector<std::uint8_t> loadCompatiblePipelineCacheData(
        const std::string& path,
        VkPhysicalDevice physicalDevice) {
    constexpr std::size_t kMaxPipelineCacheBytes = 64u * 1024u * 1024u;
    if (path.empty() || physicalDevice == VK_NULL_HANDLE) return {};

    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return {};
    const std::streamoff end = input.tellg();
    if (end <= 0 || static_cast<std::uint64_t>(end) > kMaxPipelineCacheBytes) return {};
    input.seekg(0, std::ios::beg);

    std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
    if (!input.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()))) {
        return {};
    }
    if (data.size() < sizeof(VkPipelineCacheHeaderVersionOne)) return {};

    VkPipelineCacheHeaderVersionOne header{};
    std::memcpy(&header, data.data(), sizeof(header));
    if (header.headerSize < sizeof(VkPipelineCacheHeaderVersionOne) ||
        header.headerSize > data.size() ||
        header.headerVersion != VK_PIPELINE_CACHE_HEADER_VERSION_ONE) {
        return {};
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);
    if (header.vendorID != properties.vendorID ||
        header.deviceID != properties.deviceID ||
        std::memcmp(header.pipelineCacheUUID, properties.pipelineCacheUUID, VK_UUID_SIZE) != 0) {
        return {};
    }
    return data;
}

const char* deviceTypeToString(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "INTEGRATED_GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "DISCRETE_GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "VIRTUAL_GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU";
        default: return "OTHER";
    }
}

}  // namespace

BootstrapResult VulkanRuntimeBootstrap::initialize(
    const RuntimeConfig& config,
    ValidationCollector& validationCollector
) noexcept {
    BootstrapResult result;
    result.success = false;
    result.unavailable = true;
    result.capabilities = CapabilitySnapshot::notScanned();

    try {
        // 1. Check Vulkan loader availability
        auto pfnGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            vkGetInstanceProcAddr
        );
        if (pfnGetInstanceProcAddr == nullptr) {
            result.unavailable = true;
            result.failure = {
                "VULKAN_LOADER_UNAVAILABLE",
                "vkGetInstanceProcAddr function pointer is null. Vulkan loader is unavailable.",
                false
            };
            return result;
        }

        std::uint32_t loaderApiVersion = VK_API_VERSION_1_0;
        auto pfnEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            pfnGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
        );
        if (pfnEnumerateInstanceVersion != nullptr) {
            std::uint32_t queriedVersion = VK_API_VERSION_1_0;
            if (pfnEnumerateInstanceVersion(&queriedVersion) == VK_SUCCESS) {
                loaderApiVersion = queriedVersion;
            }
        }
        result.capabilities.loaderAvailable = true;
        result.capabilities.loaderApiVersion = loaderApiVersion;

        // 2. Enumerate instance extensions and layers
        std::uint32_t instanceExtCount = 0;
        std::vector<VkExtensionProperties> availableInstanceExts;
        if (vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtCount, nullptr) == VK_SUCCESS && instanceExtCount > 0) {
            availableInstanceExts.resize(instanceExtCount);
            vkEnumerateInstanceExtensionProperties(nullptr, &instanceExtCount, availableInstanceExts.data());
        }

        std::uint32_t instanceLayerCount = 0;
        std::vector<VkLayerProperties> availableInstanceLayers;
        if (vkEnumerateInstanceLayerProperties(&instanceLayerCount, nullptr) == VK_SUCCESS && instanceLayerCount > 0) {
            availableInstanceLayers.resize(instanceLayerCount);
            vkEnumerateInstanceLayerProperties(&instanceLayerCount, availableInstanceLayers.data());
        }

        std::vector<const char*> enabledInstanceExtensions;
        std::vector<const char*> enabledInstanceLayers;

        const bool validationLayerAvail = isLayerSupported("VK_LAYER_KHRONOS_validation", availableInstanceLayers);
        result.capabilities.validationLayerAvailable = validationLayerAvail;
        if (config.debugValidationRequested && validationLayerAvail) {
            enabledInstanceLayers.push_back("VK_LAYER_KHRONOS_validation");
            result.capabilities.validationLayerEnabled = true;
        }

        const bool debugUtilsAvail = isExtensionSupported("VK_EXT_debug_utils", availableInstanceExts);
        result.capabilities.debugUtilsAvailable = debugUtilsAvail;
        if (config.debugValidationRequested && debugUtilsAvail) {
            enabledInstanceExtensions.push_back("VK_EXT_debug_utils");
            result.capabilities.debugUtilsEnabled = true;
        }

        if (loaderApiVersion < VK_API_VERSION_1_1) {
            if (isExtensionSupported("VK_KHR_get_physical_device_properties2", availableInstanceExts)) {
                enabledInstanceExtensions.push_back("VK_KHR_get_physical_device_properties2");
            }
        }

        // 3. Create VkInstance
        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "BnCam";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "BnCamEngine";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = (loaderApiVersion >= VK_API_VERSION_1_1) ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

        VkInstanceCreateInfo instanceCreateInfo{};
        instanceCreateInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        instanceCreateInfo.pApplicationInfo = &appInfo;
        instanceCreateInfo.enabledLayerCount = static_cast<std::uint32_t>(enabledInstanceLayers.size());
        instanceCreateInfo.ppEnabledLayerNames = enabledInstanceLayers.empty() ? nullptr : enabledInstanceLayers.data();
        instanceCreateInfo.enabledExtensionCount = static_cast<std::uint32_t>(enabledInstanceExtensions.size());
        instanceCreateInfo.ppEnabledExtensionNames = enabledInstanceExtensions.empty() ? nullptr : enabledInstanceExtensions.data();

        const VkResult instanceRes = vkCreateInstance(&instanceCreateInfo, nullptr, &result.handles.instance);
        if (instanceRes != VK_SUCCESS) {
            result.unavailable = (instanceRes == VK_ERROR_INCOMPATIBLE_DRIVER || instanceRes == VK_ERROR_INITIALIZATION_FAILED);
            result.failure = {
                "VULKAN_INSTANCE_CREATION_FAILED",
                "vkCreateInstance failed with result " + std::to_string(instanceRes),
                false
            };
            return result;
        }

        // 4. Create Debug Messenger (if debug utils enabled)
        if (result.capabilities.debugUtilsEnabled) {
            auto pfnCreateMessenger = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                pfnGetInstanceProcAddr(result.handles.instance, "vkCreateDebugUtilsMessengerEXT")
            );
            if (pfnCreateMessenger != nullptr) {
                VkDebugUtilsMessengerCreateInfoEXT messengerInfo{};
                messengerInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
                messengerInfo.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                                                VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
                messengerInfo.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                            VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                            VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
                messengerInfo.pfnUserCallback = debugUtilsCallback;
                messengerInfo.pUserData = &validationCollector;
                pfnCreateMessenger(result.handles.instance, &messengerInfo, nullptr, &result.handles.debugMessenger);
                if (result.handles.debugMessenger != VK_NULL_HANDLE) {
                    result.capabilities.debugMessengerCreated = true;
                    auto pfnSubmitMessage = reinterpret_cast<PFN_vkSubmitDebugUtilsMessageEXT>(
                        pfnGetInstanceProcAddr(result.handles.instance, "vkSubmitDebugUtilsMessageEXT")
                    );
                    if (pfnSubmitMessage != nullptr) {
                        VkDebugUtilsObjectNameInfoEXT objInfo{};
                        objInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT;
                        objInfo.objectType = VK_OBJECT_TYPE_INSTANCE;
                        objInfo.objectHandle = reinterpret_cast<std::uint64_t>(result.handles.instance);
                        objInfo.pObjectName = "BnCamVulkanInstance";

                        VkDebugUtilsMessengerCallbackDataEXT cbData{};
                        cbData.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CALLBACK_DATA_EXT;
                        cbData.pMessageIdName = "VUID-BnCam-SelfTest";
                        cbData.messageIdNumber = 999999;
                        cbData.pMessage = "BnCam Vulkan validation self-test message: callback and collector operational.";
                        cbData.objectCount = 1;
                        cbData.pObjects = &objInfo;

                        pfnSubmitMessage(
                            result.handles.instance,
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT,
                            VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT,
                            &cbData
                        );
                    }
                }
            }
        }

        // 5. Enumerate Physical Devices
        std::uint32_t physDeviceCount = 0;
        VkResult physRes = vkEnumeratePhysicalDevices(result.handles.instance, &physDeviceCount, nullptr);
        if (physRes != VK_SUCCESS || physDeviceCount == 0) {
            result.unavailable = true;
            result.failure = {
                "NO_PHYSICAL_DEVICE",
                "No Vulkan physical devices enumerated.",
                false
            };
            destroy(result.handles);
            return result;
        }

        std::vector<VkPhysicalDevice> physDevices(physDeviceCount);
        vkEnumeratePhysicalDevices(result.handles.instance, &physDeviceCount, physDevices.data());

        // 6. Select Physical Device and Compute Queue Family
        VkPhysicalDevice bestPhysDevice = VK_NULL_HANDLE;
        std::uint32_t bestQueueFamilyIndex = UINT32_MAX;
        int bestScore = -1;
        bool anyDeviceMissingRequiredAhb = false;

        for (auto physDev : physDevices) {
            std::uint32_t qCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qCount, nullptr);
            if (qCount == 0) continue;
            std::vector<VkQueueFamilyProperties> qProps(qCount);
            vkGetPhysicalDeviceQueueFamilyProperties(physDev, &qCount, qProps.data());

            std::uint32_t computeIdx = UINT32_MAX;
            bool dedicatedCompute = false;

            for (std::uint32_t i = 0; i < qCount; ++i) {
                if (qProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    if (!(qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                        computeIdx = i;
                        dedicatedCompute = true;
                        break;
                    } else if (computeIdx == UINT32_MAX) {
                        computeIdx = i;
                    }
                }
            }

            if (computeIdx == UINT32_MAX) continue;

            std::uint32_t devExtCount = 0;
            std::vector<VkExtensionProperties> devExts;
            if (vkEnumerateDeviceExtensionProperties(physDev, nullptr, &devExtCount, nullptr) == VK_SUCCESS && devExtCount > 0) {
                devExts.resize(devExtCount);
                vkEnumerateDeviceExtensionProperties(physDev, nullptr, &devExtCount, devExts.data());
            }

            const bool hasAhb = isExtensionSupported("VK_ANDROID_external_memory_android_hardware_buffer", devExts);
            if (config.requireAndroidHardwareBuffer && !hasAhb) {
                anyDeviceMissingRequiredAhb = true;
                continue;
            }

            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physDev, &props);

            int score = 100;
            if (dedicatedCompute) score += 50;
            if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 30;
            else if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) score += 20;

            if (score > bestScore) {
                bestScore = score;
                bestPhysDevice = physDev;
                bestQueueFamilyIndex = computeIdx;
            }
        }

        if (bestPhysDevice == VK_NULL_HANDLE) {
            result.unavailable = false;
            if (anyDeviceMissingRequiredAhb && config.requireAndroidHardwareBuffer) {
                result.failure = {
                    "UNSUPPORTED_REQUIRED_FEATURE",
                    "VK_ANDROID_external_memory_android_hardware_buffer is required by configuration but unsupported by candidate devices.",
                    false
                };
            } else {
                result.failure = {
                    "NO_COMPUTE_QUEUE_FAMILY",
                    "No Vulkan physical device with a compute queue family was found.",
                    false
                };
            }
            destroy(result.handles);
            return result;
        }

        result.handles.physicalDevice = bestPhysDevice;
        result.handles.computeQueueFamilyIndex = bestQueueFamilyIndex;

        std::uint32_t selectedQueueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(
            result.handles.physicalDevice,
            &selectedQueueFamilyCount,
            nullptr
        );
        std::vector<VkQueueFamilyProperties> selectedQueueFamilies(selectedQueueFamilyCount);
        if (selectedQueueFamilyCount > 0) {
            vkGetPhysicalDeviceQueueFamilyProperties(
                result.handles.physicalDevice,
                &selectedQueueFamilyCount,
                selectedQueueFamilies.data()
            );
        }
        const std::uint32_t availableComputeQueues =
            result.handles.computeQueueFamilyIndex < selectedQueueFamilies.size()
                ? selectedQueueFamilies[result.handles.computeQueueFamilyIndex].queueCount
                : 1u;
        const std::uint32_t requestedComputeQueues = availableComputeQueues >= 2u ? 2u : 1u;

        // 7. Enumerate selected device extensions
        std::uint32_t selectedDevExtCount = 0;
        std::vector<VkExtensionProperties> selectedDevExts;
        if (vkEnumerateDeviceExtensionProperties(result.handles.physicalDevice, nullptr, &selectedDevExtCount, nullptr) == VK_SUCCESS && selectedDevExtCount > 0) {
            selectedDevExts.resize(selectedDevExtCount);
            vkEnumerateDeviceExtensionProperties(result.handles.physicalDevice, nullptr, &selectedDevExtCount, selectedDevExts.data());
        }

        std::vector<const char*> enabledDeviceExtensions;
        const char* candidateDevExts[] = {
            "VK_ANDROID_external_memory_android_hardware_buffer",
            "VK_KHR_external_memory",
            "VK_KHR_dedicated_allocation",
            "VK_KHR_get_memory_requirements2",
            "VK_KHR_sampler_ycbcr_conversion",
            "VK_EXT_queue_family_foreign"
        };
        for (const char* ext : candidateDevExts) {
            if (isExtensionSupported(ext, selectedDevExts)) {
                enabledDeviceExtensions.push_back(ext);
            }
        }
        result.capabilities.androidHardwareBufferExtensionSupported = isExtensionSupported(
            "VK_ANDROID_external_memory_android_hardware_buffer", selectedDevExts
        );

        // 8. Create Logical Device & retrieve compute/preview queues. When the selected queue
        // family exposes at least two queues, keep RAW preview submissions on a dedicated queue so
        // long capture alignment/fusion/ISP work cannot serialize the live viewfinder behind the
        // production submission mutex. Devices exposing only one queue retain the safe legacy path.
        const float queuePriorities[2] = {0.85f, 1.0f};
        VkDeviceQueueCreateInfo queueCreateInfo{};
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueCreateInfo.queueFamilyIndex = result.handles.computeQueueFamilyIndex;
        queueCreateInfo.queueCount = requestedComputeQueues;
        queueCreateInfo.pQueuePriorities = queuePriorities;

        VkPhysicalDevice16BitStorageFeatures storage16Features{};
        storage16Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        storage16Features.storageBuffer16BitAccess = VK_TRUE;

        VkPhysicalDeviceFeatures deviceFeatures{};

        VkDeviceCreateInfo deviceCreateInfo{};
        deviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceCreateInfo.pNext = &storage16Features;
        deviceCreateInfo.queueCreateInfoCount = 1;
        deviceCreateInfo.pQueueCreateInfos = &queueCreateInfo;
        deviceCreateInfo.enabledExtensionCount = static_cast<std::uint32_t>(enabledDeviceExtensions.size());
        deviceCreateInfo.ppEnabledExtensionNames = enabledDeviceExtensions.empty() ? nullptr : enabledDeviceExtensions.data();
        deviceCreateInfo.pEnabledFeatures = &deviceFeatures;

        const VkResult deviceRes = vkCreateDevice(result.handles.physicalDevice, &deviceCreateInfo, nullptr, &result.handles.device);
        if (deviceRes != VK_SUCCESS) {
            result.unavailable = false;
            result.failure = {
                "LOGICAL_DEVICE_CREATION_FAILED",
                "vkCreateDevice failed with result " + std::to_string(deviceRes),
                false
            };
            destroy(result.handles);
            return result;
        }

        vkGetDeviceQueue(result.handles.device, result.handles.computeQueueFamilyIndex, 0, &result.handles.computeQueue);
        if (requestedComputeQueues >= 2u) {
            vkGetDeviceQueue(
                result.handles.device,
                result.handles.computeQueueFamilyIndex,
                1,
                &result.handles.previewQueue
            );
        }
        if (result.handles.computeQueue == VK_NULL_HANDLE) {
            result.unavailable = false;
            result.failure = {
                "COMPUTE_QUEUE_RETRIEVAL_FAILED",
                "vkGetDeviceQueue returned VK_NULL_HANDLE for compute queue.",
                false
            };
            destroy(result.handles);
            return result;
        }

        // 9. Create persistent command pool
        VkCommandPoolCreateInfo cmdPoolInfo{};
        cmdPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cmdPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cmdPoolInfo.queueFamilyIndex = result.handles.computeQueueFamilyIndex;

        const VkResult cmdPoolRes = vkCreateCommandPool(result.handles.device, &cmdPoolInfo, nullptr, &result.handles.commandPool);
        if (cmdPoolRes != VK_SUCCESS) {
            result.unavailable = false;
            result.failure = {
                "COMMAND_POOL_CREATION_FAILED",
                "vkCreateCommandPool failed with result " + std::to_string(cmdPoolRes),
                false
            };
            destroy(result.handles);
            return result;
        }

        // RAW multi-frame capture also owns a dedicated command pool. This is required before
        // releasing the global runtime submission mutex between capture stages: other runtime
        // backends may record commands concurrently, and VkCommandPool host access is externally
        // synchronized. If creation fails, executeRawMultiFrame keeps the legacy fully serialized
        // path on the shared command pool.
        const VkResult rawMultiFrameCmdPoolRes = vkCreateCommandPool(
            result.handles.device,
            &cmdPoolInfo,
            nullptr,
            &result.handles.rawMultiFrameCommandPool
        );
        if (rawMultiFrameCmdPoolRes != VK_SUCCESS) {
            result.handles.rawMultiFrameCommandPool = VK_NULL_HANDLE;
        }

        // RAW preview must own a different command pool even when the device exposes only one
        // compute queue. Command pools are externally synchronized Vulkan objects; separating the
        // pools lets capture release the queue mutex between stage submissions without allowing
        // concurrent host access to one command pool. A second queue remains an optimization.
        const VkResult previewCmdPoolRes = vkCreateCommandPool(
            result.handles.device,
            &cmdPoolInfo,
            nullptr,
            &result.handles.previewCommandPool
        );
        if (previewCmdPoolRes != VK_SUCCESS) {
            // Keep the old fully serialized path as the safe fallback. Without an isolated preview
            // command pool we must not interleave host command-buffer operations on one pool.
            result.handles.previewQueue = VK_NULL_HANDLE;
            result.handles.previewCommandPool = VK_NULL_HANDLE;
        }

        // 10. Create persistent descriptor pool
        VkDescriptorPoolSize poolSizes[] = {
            { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 64 },
            { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 64 },
            { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 64 },
            { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 64 }
        };
        VkDescriptorPoolCreateInfo descPoolInfo{};
        descPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        descPoolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
        descPoolInfo.maxSets = 128;
        descPoolInfo.poolSizeCount = 4;
        descPoolInfo.pPoolSizes = poolSizes;

        const VkResult descPoolRes = vkCreateDescriptorPool(result.handles.device, &descPoolInfo, nullptr, &result.handles.descriptorPool);
        if (descPoolRes != VK_SUCCESS) {
            result.unavailable = false;
            result.failure = {
                "DESCRIPTOR_POOL_CREATION_FAILED",
                "vkCreateDescriptorPool failed with result " + std::to_string(descPoolRes),
                false
            };
            destroy(result.handles);
            return result;
        }

        // 11. Create persistent pipeline cache. Reuse compatible driver data from the previous
        // process so known RAW-preview pipelines do not need full driver-side recompilation.
        const std::vector<std::uint8_t> pipelineCacheInitialData =
            loadCompatiblePipelineCacheData(config.pipelineCachePath, result.handles.physicalDevice);
        VkPipelineCacheCreateInfo cacheInfo{};
        cacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        cacheInfo.initialDataSize = pipelineCacheInitialData.size();
        cacheInfo.pInitialData = pipelineCacheInitialData.empty()
            ? nullptr : pipelineCacheInitialData.data();

        VkResult cacheRes = vkCreatePipelineCache(
            result.handles.device, &cacheInfo, nullptr, &result.handles.pipelineCache);
        if (cacheRes != VK_SUCCESS && !pipelineCacheInitialData.empty()) {
            // A stale/driver-invalidated cache must never make Vulkan startup fail.
            cacheInfo.initialDataSize = 0u;
            cacheInfo.pInitialData = nullptr;
            cacheRes = vkCreatePipelineCache(
                result.handles.device, &cacheInfo, nullptr, &result.handles.pipelineCache);
        }
        if (cacheRes != VK_SUCCESS) {
            result.unavailable = false;
            result.failure = {
                "PIPELINE_CACHE_CREATION_FAILED",
                "vkCreatePipelineCache failed with result " + std::to_string(cacheRes),
                false
            };
            destroy(result.handles);
            return result;
        }
        VulkanPipelineCacheRegistry::publish(
            result.handles.device, result.handles.pipelineCache, config.pipelineCachePath);

        // 12. Create VMA allocator
        VmaCreateRequest vmaReq{};
        vmaReq.instance = result.handles.instance;
        vmaReq.physicalDevice = result.handles.physicalDevice;
        vmaReq.device = result.handles.device;
        vmaReq.vulkanApiVersion = (loaderApiVersion >= VK_API_VERSION_1_1) ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

        std::string vmaFailureReason;
        if (!result.handles.allocator.create(vmaReq, vmaFailureReason)) {
            result.unavailable = false;
            result.failure = {
                "VMA_CREATION_FAILED",
                "VulkanAllocatorOwner::create failed: " + vmaFailureReason,
                false
            };
            destroy(result.handles);
            return result;
        }

        // 13. Populate capability snapshot
        VkPhysicalDeviceProperties devProps{};
        vkGetPhysicalDeviceProperties(result.handles.physicalDevice, &devProps);

        result.capabilities.schemaVersion = kRuntimeSchemaVersion;
        result.capabilities.selectedDeviceName = devProps.deviceName;
        result.capabilities.vendorId = devProps.vendorID;
        result.capabilities.deviceId = devProps.deviceID;
        result.capabilities.deviceApiVersion = devProps.apiVersion;
        result.capabilities.driverVersion = devProps.driverVersion;
        result.capabilities.deviceType = deviceTypeToString(devProps.deviceType);
        result.capabilities.computeQueueFamilyIndex = result.handles.computeQueueFamilyIndex;
        result.capabilities.computeQueueCount =
            result.handles.previewQueue != VK_NULL_HANDLE ? 2u : 1u;
        result.capabilities.vmaReady = result.handles.allocator.isReady();

        for (const auto* name : enabledInstanceExtensions) {
            result.capabilities.enabledExtensions.push_back(name);
        }
        for (const auto* name : enabledDeviceExtensions) {
            result.capabilities.enabledExtensions.push_back(name);
        }
        if (result.capabilities.validationLayerEnabled) {
            result.capabilities.enabledFeatures.push_back("validation_layer");
        }

        // Query memory properties with unique heap index deduplication
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(result.handles.physicalDevice, &memProps);

        std::set<std::uint32_t> devLocalHeapIndices;
        std::set<std::uint32_t> hostVisibleHeapIndices;

        for (std::uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
            const std::uint32_t heapIdx = memProps.memoryTypes[i].heapIndex;
            if (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                devLocalHeapIndices.insert(heapIdx);
            }
            if (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
                hostVisibleHeapIndices.insert(heapIdx);
            }
        }
        for (std::uint32_t i = 0; i < memProps.memoryHeapCount; ++i) {
            if (memProps.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
                devLocalHeapIndices.insert(i);
            }
        }

        result.capabilities.deviceLocalMemoryBytes = 0;
        for (const std::uint32_t heapIdx : devLocalHeapIndices) {
            if (heapIdx < memProps.memoryHeapCount) {
                result.capabilities.deviceLocalMemoryBytes += memProps.memoryHeaps[heapIdx].size;
            }
        }

        result.capabilities.hostVisibleMemoryBytes = 0;
        for (const std::uint32_t heapIdx : hostVisibleHeapIndices) {
            if (heapIdx < memProps.memoryHeapCount) {
                result.capabilities.hostVisibleMemoryBytes += memProps.memoryHeaps[heapIdx].size;
            }
        }

        // Query queue family properties for timestamp support
        std::uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(result.handles.physicalDevice, &qCount, nullptr);
        if (qCount > 0 && result.handles.computeQueueFamilyIndex < qCount) {
            std::vector<VkQueueFamilyProperties> qProps(qCount);
            vkGetPhysicalDeviceQueueFamilyProperties(result.handles.physicalDevice, &qCount, qProps.data());
            result.capabilities.timestampQueriesSupported = (qProps[result.handles.computeQueueFamilyIndex].timestampValidBits > 0);
        }

        const bool isVulkan11 = (devProps.apiVersion >= VK_API_VERSION_1_1);
        const bool isVulkan12 = (devProps.apiVersion >= VK_API_VERSION_1_2);
        result.capabilities.timelineSemaphoresSupported = isVulkan12;

        std::uint32_t queriedDevExtCount = 0;
        std::vector<VkExtensionProperties> availableDevExts;
        if (vkEnumerateDeviceExtensionProperties(result.handles.physicalDevice, nullptr, &queriedDevExtCount, nullptr) == VK_SUCCESS && queriedDevExtCount > 0) {
            availableDevExts.resize(queriedDevExtCount);
            vkEnumerateDeviceExtensionProperties(result.handles.physicalDevice, nullptr, &queriedDevExtCount, availableDevExts.data());
        }

        result.capabilities.records.clear();
        const auto addRec = [&](const std::string& k, CapabilityStatus s, const std::string& v, const std::string& r) {
            result.capabilities.records.push_back({k, s, v, r});
        };

        addRec("vulkan_loader", CapabilityStatus::AVAILABLE, "API " + std::to_string(loaderApiVersion), "verified");
        addRec("instance_api_version", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "Vulkan " + std::to_string(VK_VERSION_MAJOR(loaderApiVersion)) + "." + std::to_string(VK_VERSION_MINOR(loaderApiVersion)), "created");
        addRec("physical_device", CapabilityStatus::AVAILABLE, devProps.deviceName, "selected");
        addRec("device_type", CapabilityStatus::AVAILABLE, deviceTypeToString(devProps.deviceType), "queried");
        addRec("queue_families", CapabilityStatus::AVAILABLE, std::to_string(qCount) + " queue families", "queried");
        addRec("compute_queue", CapabilityStatus::ENABLED_AS_CORE_FEATURE, "family " + std::to_string(result.handles.computeQueueFamilyIndex), "active");
        addRec(
            "preview_compute_queue",
            result.handles.previewQueue != VK_NULL_HANDLE
                ? CapabilityStatus::ENABLED_AS_CORE_FEATURE
                : CapabilityStatus::SUPPORTED_NOT_ENABLED,
            result.handles.previewQueue != VK_NULL_HANDLE ? "dedicated_queue_1" : "shared_queue_0",
            result.handles.previewQueue != VK_NULL_HANDLE
                ? "RAW preview can submit independently from capture processing."
                : "Selected queue family exposes only one usable queue; preview shares capture queue."
        );
        addRec(
            "raw_multiframe_command_pool",
            result.handles.rawMultiFrameCommandPool != VK_NULL_HANDLE
                ? CapabilityStatus::ENABLED_AS_CORE_FEATURE
                : CapabilityStatus::SUPPORTED_NOT_ENABLED,
            result.handles.rawMultiFrameCommandPool != VK_NULL_HANDLE ? "isolated_capture_pool" : "shared_runtime_pool",
            result.handles.rawMultiFrameCommandPool != VK_NULL_HANDLE
                ? "RAW multi-frame command recording can interleave safely with preview/other runtime backends."
                : "RAW multi-frame keeps full runtime serialization because capture command-pool isolation is unavailable."
        );
        addRec(
            "preview_command_pool",
            result.handles.previewCommandPool != VK_NULL_HANDLE
                ? CapabilityStatus::ENABLED_AS_CORE_FEATURE
                : CapabilityStatus::SUPPORTED_NOT_ENABLED,
            result.handles.previewCommandPool != VK_NULL_HANDLE ? "isolated_preview_pool" : "shared_capture_pool",
            result.handles.previewCommandPool != VK_NULL_HANDLE
                ? "RAW preview host command-buffer ownership is isolated from capture processing."
                : "Preview/capture command-buffer recording remains fully serialized for Vulkan correctness."
        );
        addRec("command_pool", CapabilityStatus::ENABLED_AS_CORE_FEATURE, "lifecycle-persistent VkCommandPool object", "active");
        addRec("descriptor_pool", CapabilityStatus::ENABLED_AS_CORE_FEATURE, "lifecycle-persistent VkDescriptorPool object", "active");
        addRec(
            "pipeline_cache",
            CapabilityStatus::ENABLED_AS_CORE_FEATURE,
            "lifecycle-persistent VkPipelineCache object",
            pipelineCacheInitialData.empty()
                ? "disk cache enabled; no compatible initial data loaded"
                : "disk cache enabled; loaded " + std::to_string(pipelineCacheInitialData.size()) + " bytes"
        );
        addRec("vma_allocator", result.capabilities.vmaReady ? CapabilityStatus::ENABLED_AS_CORE_FEATURE : CapabilityStatus::MISSING, result.capabilities.vmaReady ? "VmaAllocator 3.3.0" : "absent", result.capabilities.vmaReady ? "active" : "failed");

        addRec("memory_heaps", CapabilityStatus::AVAILABLE, std::to_string(result.capabilities.deviceLocalMemoryBytes / (1024 * 1024)) + " MB device local", "queried");
        addRec("memory_types", CapabilityStatus::AVAILABLE, std::to_string(memProps.memoryTypeCount) + " memory types", "queried");
        addRec("storage_buffers", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "max range " + std::to_string(devProps.limits.maxStorageBufferRange) + " bytes", "queried");
        addRec("storage_images", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "max 2D " + std::to_string(devProps.limits.maxImageDimension2D) + "px", "queried");

        if (result.capabilities.timestampQueriesSupported) {
            addRec("timestamps", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "period=" + std::to_string(devProps.limits.timestampPeriod) + "ns", "supported");
        } else {
            addRec("timestamps", CapabilityStatus::MISSING, "0 bits", "not supported");
        }

        addRec("subgroups", isVulkan11 ? CapabilityStatus::SUPPORTED_BY_CORE_VERSION : CapabilityStatus::SUPPORTED_NOT_ENABLED, "subgroup ops", isVulkan11 ? "Vulkan 1.1 core" : "extension check");
        addRec("synchronization", isVulkan12 ? CapabilityStatus::SUPPORTED_BY_CORE_VERSION : CapabilityStatus::SUPPORTED_BY_EXTENSION, "barriers/fences", isVulkan12 ? "Vulkan 1.2 core timeline semaphores" : "Vulkan 1.1 core sync");
        addRec("descriptor_indexing", isVulkan12 ? CapabilityStatus::SUPPORTED_BY_CORE_VERSION : (isExtensionSupported("VK_EXT_descriptor_indexing", availableDevExts) ? CapabilityStatus::SUPPORTED_BY_EXTENSION : CapabilityStatus::SUPPORTED_NOT_ENABLED), "descriptor indexing", isVulkan12 ? "Vulkan 1.2 core" : "optional");

        if (result.capabilities.validationLayerEnabled) {
            addRec("validation_layer", CapabilityStatus::ENABLED_AS_EXTENSION, "VK_LAYER_KHRONOS_validation", "enabled on instance");
        } else if (result.capabilities.validationLayerAvailable) {
            addRec("validation_layer", CapabilityStatus::SUPPORTED_NOT_ENABLED, "VK_LAYER_KHRONOS_validation", "available but not enabled");
        } else {
            addRec("validation_layer", CapabilityStatus::MISSING, "VK_LAYER_KHRONOS_validation", "not available on system");
        }

        if (result.capabilities.debugUtilsEnabled) {
            addRec("VK_EXT_debug_utils", CapabilityStatus::ENABLED_AS_EXTENSION, "VK_EXT_debug_utils", "enabled on instance");
        } else if (result.capabilities.debugUtilsAvailable) {
            addRec("VK_EXT_debug_utils", CapabilityStatus::SUPPORTED_NOT_ENABLED, "VK_EXT_debug_utils", "available but not enabled");
        } else {
            addRec("VK_EXT_debug_utils", CapabilityStatus::MISSING, "VK_EXT_debug_utils", "not available on system");
        }

        if (isVulkan11) {
            addRec("external_memory", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "VK_KHR_external_memory", "promoted to Vulkan 1.1 core");
            addRec("dedicated_allocation", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "VK_KHR_dedicated_allocation", "promoted to Vulkan 1.1 core");
            addRec("external_semaphore", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "VK_KHR_external_semaphore", "promoted to Vulkan 1.1 core");
            addRec("external_fence", CapabilityStatus::SUPPORTED_BY_CORE_VERSION, "VK_KHR_external_fence", "promoted to Vulkan 1.1 core");
        } else {
            addRec("external_memory", isExtensionSupported("VK_KHR_external_memory", availableDevExts) ? CapabilityStatus::SUPPORTED_BY_EXTENSION : CapabilityStatus::MISSING, "VK_KHR_external_memory", "extension");
            addRec("dedicated_allocation", isExtensionSupported("VK_KHR_dedicated_allocation", availableDevExts) ? CapabilityStatus::SUPPORTED_BY_EXTENSION : CapabilityStatus::MISSING, "VK_KHR_dedicated_allocation", "extension");
            addRec("external_semaphore", isExtensionSupported("VK_KHR_external_semaphore", availableDevExts) ? CapabilityStatus::SUPPORTED_BY_EXTENSION : CapabilityStatus::MISSING, "VK_KHR_external_semaphore", "extension");
            addRec("external_fence", isExtensionSupported("VK_KHR_external_fence", availableDevExts) ? CapabilityStatus::SUPPORTED_BY_EXTENSION : CapabilityStatus::MISSING, "VK_KHR_external_fence", "extension");
        }

        bool ahbProcAddrValid = false;
        if (result.capabilities.androidHardwareBufferExtensionSupported && result.handles.device != VK_NULL_HANDLE) {
            auto pfnGetAHBProps = reinterpret_cast<void*>(
                vkGetDeviceProcAddr(result.handles.device, "vkGetAndroidHardwareBufferPropertiesANDROID")
            );
            ahbProcAddrValid = (pfnGetAHBProps != nullptr);
        }

        if (result.capabilities.androidHardwareBufferExtensionSupported && ahbProcAddrValid) {
            addRec("VK_ANDROID_external_memory_android_hardware_buffer", CapabilityStatus::ENABLED_AS_EXTENSION, "VK_ANDROID_external_memory_android_hardware_buffer", "enabled on VkDevice; vkGetAndroidHardwareBufferPropertiesANDROID proc addr verified");
        } else if (result.capabilities.androidHardwareBufferExtensionSupported) {
            result.capabilities.androidHardwareBufferExtensionSupported = false;
            addRec("VK_ANDROID_external_memory_android_hardware_buffer", CapabilityStatus::MISSING, "VK_ANDROID_external_memory_android_hardware_buffer", "extension reported but vkGetAndroidHardwareBufferPropertiesANDROID proc addr was null");
        } else {
            addRec("VK_ANDROID_external_memory_android_hardware_buffer", CapabilityStatus::MISSING, "VK_ANDROID_external_memory_android_hardware_buffer", "extension missing on physical device");
        }

        addRec("yuv_ahardwarebuffer_import", CapabilityStatus::REQUIRED_LATER, "direct YUV import", "Phase 3B import boundary");
        addRec("raw10_ahardwarebuffer_import", CapabilityStatus::REQUIRED_LATER, "direct RAW10 import", "Phase 3B import boundary");
        addRec("raw_sensor_ahardwarebuffer_import", CapabilityStatus::REQUIRED_LATER, "direct RAW_SENSOR import", "Phase 3B import boundary");

        result.capabilities.missingRequirements = {
            "yuv_ahardwarebuffer_import_pending_phase_3b",
            "raw10_ahardwarebuffer_import_pending_phase_3b",
            "raw_sensor_ahardwarebuffer_import_pending_phase_3b",
        };

        result.success = true;
        result.unavailable = false;
        result.failure = {};
        return result;

    } catch (const std::exception& ex) {
        result.success = false;
        result.unavailable = false;
        result.failure = {
            "VULKAN_BOOTSTRAP_EXCEPTION",
            std::string("Native exception during Vulkan bootstrap: ") + ex.what(),
            false
        };
        destroy(result.handles);
        return result;
    } catch (...) {
        result.success = false;
        result.unavailable = false;
        result.failure = {
            "VULKAN_BOOTSTRAP_EXCEPTION",
            "Unknown native exception during Vulkan bootstrap.",
            false
        };
        destroy(result.handles);
        return result;
    }
}

RuntimeFailure VulkanRuntimeBootstrap::destroy(OwnedRuntimeHandles& handles) noexcept {
    if (handles.empty()) {
        return {};
    }

    try {
        if (handles.device != VK_NULL_HANDLE) {
            if (!VulkanRuntime::instance().isGpuStalled() && !VulkanRuntime::instance().isQuarantined()) {
                vkDeviceWaitIdle(handles.device);
            }

            if (handles.pipelineCache != VK_NULL_HANDLE) {
                VulkanPipelineCacheRegistry::clear(handles.device, handles.pipelineCache);
                vkDestroyPipelineCache(handles.device, handles.pipelineCache, nullptr);
                handles.pipelineCache = VK_NULL_HANDLE;
            }
            if (handles.descriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(handles.device, handles.descriptorPool, nullptr);
                handles.descriptorPool = VK_NULL_HANDLE;
            }
            if (handles.rawMultiFrameCommandPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(handles.device, handles.rawMultiFrameCommandPool, nullptr);
                handles.rawMultiFrameCommandPool = VK_NULL_HANDLE;
            }
            if (handles.previewCommandPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(handles.device, handles.previewCommandPool, nullptr);
                handles.previewCommandPool = VK_NULL_HANDLE;
            }
            if (handles.commandPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(handles.device, handles.commandPool, nullptr);
                handles.commandPool = VK_NULL_HANDLE;
            }

            handles.allocator.destroy();

            vkDestroyDevice(handles.device, nullptr);
            handles.device = VK_NULL_HANDLE;
            handles.computeQueue = VK_NULL_HANDLE;
            handles.previewQueue = VK_NULL_HANDLE;
            handles.computeQueueFamilyIndex = UINT32_MAX;
        } else {
            handles.allocator.destroy();
        }

        if (handles.instance != VK_NULL_HANDLE && handles.debugMessenger != VK_NULL_HANDLE) {
            auto pfnGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(vkGetInstanceProcAddr);
            if (pfnGetInstanceProcAddr != nullptr) {
                auto pfnDestroyMessenger = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                    pfnGetInstanceProcAddr(handles.instance, "vkDestroyDebugUtilsMessengerEXT")
                );
                if (pfnDestroyMessenger != nullptr) {
                    pfnDestroyMessenger(handles.instance, handles.debugMessenger, nullptr);
                }
            }
            handles.debugMessenger = VK_NULL_HANDLE;
        }

        if (handles.instance != VK_NULL_HANDLE) {
            vkDestroyInstance(handles.instance, nullptr);
            handles.instance = VK_NULL_HANDLE;
        }

        handles.physicalDevice = VK_NULL_HANDLE;
        handles = {};
    } catch (...) {
        return {
            "VULKAN_DESTROY_EXCEPTION",
            "Native exception occurred during Vulkan resource destruction.",
            false
        };
    }
    return {};
}

}  // namespace bncam::vulkan

