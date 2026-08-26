#include "VulkanJson.h"

#include <iomanip>
#include <sstream>

namespace bncam::vulkan::json {

std::string escape(const std::string& value) {
    std::ostringstream out;
    for (const unsigned char ch : value) {
        switch (ch) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(ch) << std::dec;
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

std::string quote(const std::string& value) {
    return "\"" + escape(value) + "\"";
}

std::string boolean(bool value) {
    return value ? "true" : "false";
}

std::string unsignedNumber(std::uint64_t value) {
    return std::to_string(value);
}

std::string signedNumber(std::int64_t value) {
    return std::to_string(value);
}

std::string stringArray(const std::vector<std::string>& values) {
    std::ostringstream out;
    out << '[';
    for (std::size_t index = 0; index < values.size(); ++index) {
        if (index != 0) out << ',';
        out << quote(values[index]);
    }
    out << ']';
    return out.str();
}

}  // namespace bncam::vulkan::json
