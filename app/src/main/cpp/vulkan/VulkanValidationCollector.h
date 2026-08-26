#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace bncam::vulkan {

enum class ValidationSeverity {
    VERBOSE,
    INFO,
    WARNING,
    ERROR,
};

enum class ValidationMessageType {
    GENERAL,
    VALIDATION,
    PERFORMANCE,
    DEVICE_ADDRESS_BINDING,
    UNKNOWN,
};

const char* toString(ValidationSeverity severity) noexcept;
const char* toString(ValidationMessageType type) noexcept;

struct ValidationObject {
    std::string type;
    std::string name;
};

struct ValidationMessageInput {
    ValidationSeverity severity = ValidationSeverity::INFO;
    ValidationMessageType type = ValidationMessageType::UNKNOWN;
    std::int32_t messageIdNumber = 0;
    std::string messageIdName;
    std::string message;
    std::vector<ValidationObject> objects;
    std::uint64_t timestampEpochMs = 0;
};

struct ValidationMessageRecord {
    ValidationMessageInput value;
    std::string fingerprint;
    std::uint64_t repeatCount = 1;
};

struct ValidationSnapshot {
    std::vector<ValidationMessageRecord> records;
    std::uint64_t droppedMessageCount = 0;
    std::uint64_t totalMessageCount = 0;

    std::string toJson() const;
    std::string toHumanReadable() const;
};

/**
 * Native-only bounded collector for VK_EXT_debug_utils output.
 * The future Vulkan callback may call record() from any driver thread. record() performs no I/O,
 * no JNI, and no unbounded allocation. Export happens later from a safe application thread.
 */
class ValidationCollector final {
public:
    explicit ValidationCollector(std::size_t maxUniqueRecords = 256,
                                 std::size_t maxMessageBytes = 1024,
                                 std::size_t maxObjectsPerMessage = 16);

    void record(ValidationMessageInput input) noexcept;
    ValidationSnapshot snapshot() const;
    void clear() noexcept;

private:
    static std::string fingerprint(const ValidationMessageInput& input);
    static std::string bounded(std::string value, std::size_t limit);

    const std::size_t maxUniqueRecords_;
    const std::size_t maxMessageBytes_;
    const std::size_t maxObjectsPerMessage_;
    mutable std::mutex mutex_;
    std::vector<ValidationMessageRecord> records_;
    std::unordered_map<std::string, std::size_t> recordIndexByFingerprint_;
    std::atomic<std::uint64_t> droppedMessageCount_{0};
    std::atomic<std::uint64_t> totalMessageCount_{0};
};

}  // namespace bncam::vulkan
