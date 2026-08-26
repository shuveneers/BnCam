# Vulkan third-party dependencies

## Vulkan Memory Allocator

- Project: Vulkan Memory Allocator
- Upstream: GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator
- Pinned version: 3.3.0 (`v3.3.0`)
- License: MIT
- Local path: `app/src/main/cpp/third_party/vma-3.3.0`
- Fetch tools: `app/tools/fetch_vma.ps1`, `app/tools/fetch_vma.sh`

VMA is used only for BnCam-owned Vulkan allocations. Android hardware buffers remain external
resources and follow Vulkan Android external-memory import rules.

## vk-bootstrap

Not included. BnCam uses one direct, compute-only Vulkan bootstrap to preserve explicit ownership,
feature negotiation and diagnostics.
