# Vulkan Memory Allocator dependency

BnCam pins the official AMD/GPUOpen Vulkan Memory Allocator to **v3.3.0**.

The large single-header source is deliberately fetched by the project tool rather than duplicated
inside transfer archives. Run from the project root on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File app/tools/fetch_vma.ps1
```

or on a Unix-like development host:

```bash
bash app/tools/fetch_vma.sh
```

The script downloads only the immutable tag URL and verifies both the version marker and the
pinned upstream Git blob SHA (`8df03649b5b97acc1e43839d2857d32d267958ca`). Commit/vendor `vk_mem_alloc.h` after fetching for reproducible offline builds.

VMA owns normal BnCam Vulkan buffers/images. Imported `AHardwareBuffer` memory remains externally
owned and follows Android dedicated import/binding rules rather than an ordinary VMA allocation.
