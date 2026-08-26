#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan::json {

std::string escape(const std::string& value);
std::string quote(const std::string& value);
std::string boolean(bool value);
std::string unsignedNumber(std::uint64_t value);
std::string signedNumber(std::int64_t value);
std::string stringArray(const std::vector<std::string>& values);

}  // namespace bncam::vulkan::json
