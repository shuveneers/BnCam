#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
namespace bncam::vulkan::neural {
using Sha256Digest = std::array<std::uint8_t, 32>;
Sha256Digest sha256(const void* data, std::size_t size) noexcept;
std::string sha256Hex(const Sha256Digest& digest);
} // namespace bncam::vulkan::neural
