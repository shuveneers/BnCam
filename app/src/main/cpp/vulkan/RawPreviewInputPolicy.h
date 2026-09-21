#pragma once

#include <cstddef>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <limits>

namespace bncam::vulkan {

// The storage-buffer shaders address bytes with uint32 offsets. Preserve Camera2's declared
// layout in staging; a mapper's different row padding is a copy concern, not a shader mutation.
struct RawPreviewInputLayout {
    std::uint32_t rowBytes = 0;
    std::uint64_t sourceSpan = 0;
    std::uint64_t destinationBytes = 0;
    std::uint64_t copiedBytes = 0;
    const char* rejection = "none";
    bool valid() const { return rowBytes != 0; }
};

struct RawPreviewRowCopyTiming {
    double memcpyMs = 0.0;
    double paddingMs = 0.0;
};

inline RawPreviewInputLayout rawPreviewInputLayout(
        std::uint32_t format, std::uint32_t width, std::uint32_t height,
        std::uint32_t sourceRowStride, std::uint32_t sourcePixelStride,
        std::uint32_t destinationRowStride, std::uint32_t destinationPixelStride) {
    RawPreviewInputLayout result;
    const auto reject = [&](const char* reason) {
        result.rejection = reason;
        return result;
    };
    if (width == 0 || height == 0) return reject("EMPTY_RAW_LAYOUT");
    std::uint64_t rowBytes = 0;
    if (format == 37u) {
        if ((width % 4u) != 0u || (height % 2u) != 0u) return reject("RAW10_DIMENSIONS_INVALID");
        // Some AHB mappers describe packed bytes with stride 1; neither 0 nor 1 means a
        // byte-per-pixel RAW10 image. The five-byte/four-pixel representation is unchanged.
        if (sourcePixelStride > 1u || destinationPixelStride != 0u) return reject("RAW10_PIXEL_STRIDE_INVALID");
        rowBytes = static_cast<std::uint64_t>(width) * 5u / 4u;
    } else if (format == 32u) {
        if (sourcePixelStride != 2u || destinationPixelStride != 2u) return reject("RAW16_PIXEL_STRIDE_INVALID");
        rowBytes = static_cast<std::uint64_t>(width) * 2u;
    } else {
        return reject("RAW_FORMAT_UNSUPPORTED");
    }
    if (rowBytes > sourceRowStride || rowBytes > destinationRowStride) return reject("RAW_ROW_STRIDE_TOO_SMALL");
    const auto span = static_cast<std::uint64_t>(height - 1u) * sourceRowStride + rowBytes;
    const auto allocation = (static_cast<std::uint64_t>(height) * destinationRowStride + 3u) & ~std::uint64_t{3u};
    if (span > std::numeric_limits<std::size_t>::max() ||
        allocation > std::numeric_limits<std::uint32_t>::max()) return reject("RAW_LAYOUT_ADDRESS_OVERFLOW");
    result.rowBytes = static_cast<std::uint32_t>(rowBytes);
    result.sourceSpan = span;
    result.destinationBytes = allocation;
    result.copiedBytes = rowBytes * height;
    return result;
}

inline bool copyRawPreviewRows(void* destination, std::size_t capacity, const void* source,
        std::size_t sourceCapacity, std::uint32_t height, std::uint32_t sourceStride,
        std::uint32_t destinationStride, const RawPreviewInputLayout& layout,
        RawPreviewRowCopyTiming* timing = nullptr) {
    if (!layout.valid() || !destination || !source || capacity < layout.destinationBytes ||
        sourceCapacity < layout.sourceSpan) return false;
    auto* out = static_cast<std::uint8_t*>(destination);
    const auto* in = static_cast<const std::uint8_t*>(source);
    for (std::uint32_t row = 0; row < height; ++row) {
        const auto offset = static_cast<std::size_t>(row) * destinationStride;
        if (timing) {
            const auto copyStart = std::chrono::steady_clock::now();
            std::memcpy(out + offset, in + static_cast<std::size_t>(row) * sourceStride, layout.rowBytes);
            const auto paddingStart = std::chrono::steady_clock::now();
            std::memset(out + offset + layout.rowBytes, 0, destinationStride - layout.rowBytes);
            const auto paddingEnd = std::chrono::steady_clock::now();
            timing->memcpyMs += std::chrono::duration<double, std::milli>(paddingStart - copyStart).count();
            timing->paddingMs += std::chrono::duration<double, std::milli>(paddingEnd - paddingStart).count();
        } else {
            std::memcpy(out + offset, in + static_cast<std::size_t>(row) * sourceStride, layout.rowBytes);
            std::memset(out + offset + layout.rowBytes, 0, destinationStride - layout.rowBytes);
        }
    }
    const auto used = static_cast<std::size_t>(height) * destinationStride;
    if (timing) {
        const auto start = std::chrono::steady_clock::now();
        std::memset(out + used, 0, static_cast<std::size_t>(layout.destinationBytes) - used);
        timing->paddingMs += std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - start).count();
    } else {
        std::memset(out + used, 0, static_cast<std::size_t>(layout.destinationBytes) - used);
    }
    return true;
}

inline bool rawPreviewBlobContract(std::uint32_t format, std::uint32_t width,
        std::uint32_t height, std::uint32_t layers, std::uint64_t usage, std::uint64_t bytes) {
    // Android BLOB=0x21, GPU_DATA_BUFFER=1<<24. Camera RAW image formats never satisfy this.
    return format == 0x21u && height == 1u && layers == 1u &&
        (usage & (1ull << 24u)) != 0 && bytes > 0 && width >= bytes;
}

} // namespace bncam::vulkan
