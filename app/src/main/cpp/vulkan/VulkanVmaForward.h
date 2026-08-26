#pragma once

// Matches the opaque handle declared by Vulkan Memory Allocator.
struct VmaAllocator_T;
using VmaAllocator = VmaAllocator_T*;

struct VmaAllocation_T;
using VmaAllocation = VmaAllocation_T*;
