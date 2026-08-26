#include "VulkanValidationCollector.h"
#include "VulkanJson.h"

#include <algorithm>
#include <iomanip>
#include <sstream>
#include <utility>

namespace bncam::vulkan {

const char* toString(ValidationSeverity severity) noexcept {
    switch (severity) {
        case ValidationSeverity::VERBOSE: return "VERBOSE";
        case ValidationSeverity::INFO: return "INFO";
        case ValidationSeverity::WARNING: return "WARNING";
        case ValidationSeverity::ERROR: return "ERROR";
    }
    return "INFO";
}

const char* toString(ValidationMessageType type) noexcept {
    switch (type) {
        case ValidationMessageType::GENERAL: return "GENERAL";
        case ValidationMessageType::VALIDATION: return "VALIDATION";
        case ValidationMessageType::PERFORMANCE: return "PERFORMANCE";
        case ValidationMessageType::DEVICE_ADDRESS_BINDING: return "DEVICE_ADDRESS_BINDING";
        case ValidationMessageType::UNKNOWN: return "UNKNOWN";
    }
    return "UNKNOWN";
}

ValidationCollector::ValidationCollector(
    std::size_t maxUniqueRecords,
    std::size_t maxMessageBytes,
    std::size_t maxObjectsPerMessage
) : maxUniqueRecords_(std::max<std::size_t>(1, maxUniqueRecords)),
    maxMessageBytes_(std::max<std::size_t>(64, maxMessageBytes)),
    maxObjectsPerMessage_(std::max<std::size_t>(1, maxObjectsPerMessage)) {
    records_.reserve(maxUniqueRecords_);
}

std::string ValidationCollector::bounded(std::string value, std::size_t limit) {
    if (value.size() <= limit) return value;
    value.resize(limit);
    return value;
}

std::string ValidationCollector::fingerprint(const ValidationMessageInput& input) {
    // Stable FNV-1a 64-bit fingerprint. It is diagnostic deduplication, not a security hash.
    std::uint64_t hash = 1469598103934665603ULL;
    const auto feed = [&hash](const std::string& text) {
        for (const unsigned char ch : text) {
            hash ^= ch;
            hash *= 1099511628211ULL;
        }
    };
    feed(toString(input.severity));
    feed(toString(input.type));
    feed(std::to_string(input.messageIdNumber));
    feed(input.messageIdName);
    feed(input.message);
    for (const auto& object : input.objects) {
        feed(object.type);
        feed(object.name);
    }
    std::ostringstream out;
    out << std::hex << std::setw(16) << std::setfill('0') << hash;
    return out.str();
}

void ValidationCollector::record(ValidationMessageInput input) noexcept {
    bool totalCounted = false;
    try {
        input.message = bounded(std::move(input.message), maxMessageBytes_);
        input.messageIdName = bounded(std::move(input.messageIdName), 256);
        if (input.objects.size() > maxObjectsPerMessage_) {
            input.objects.resize(maxObjectsPerMessage_);
        }
        for (auto& object : input.objects) {
            object.type = bounded(std::move(object.type), 128);
            object.name = bounded(std::move(object.name), 256);
        }

        const std::string key = fingerprint(input);
        totalMessageCount_.fetch_add(1, std::memory_order_relaxed);
        totalCounted = true;

        // Driver callback threads must never wait behind diagnostics export or another callback.
        // Contended messages are dropped and counted rather than blocking the Vulkan driver.
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            droppedMessageCount_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        const auto existing = recordIndexByFingerprint_.find(key);
        if (existing != recordIndexByFingerprint_.end()) {
            ++records_[existing->second].repeatCount;
            return;
        }
        if (records_.size() >= maxUniqueRecords_) {
            droppedMessageCount_.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        recordIndexByFingerprint_[key] = records_.size();
        records_.push_back({std::move(input), key, 1});
    } catch (...) {
        // A Vulkan validation callback must never throw across the driver boundary.
        if (!totalCounted) {
            totalMessageCount_.fetch_add(1, std::memory_order_relaxed);
        }
        droppedMessageCount_.fetch_add(1, std::memory_order_relaxed);
    }
}

ValidationSnapshot ValidationCollector::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return {
        records_,
        droppedMessageCount_.load(std::memory_order_relaxed),
        totalMessageCount_.load(std::memory_order_relaxed),
    };
}

void ValidationCollector::clear() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    records_.clear();
    recordIndexByFingerprint_.clear();
    droppedMessageCount_.store(0, std::memory_order_relaxed);
    totalMessageCount_.store(0, std::memory_order_relaxed);
}

std::string ValidationSnapshot::toJson() const {
    std::ostringstream out;
    out << '{'
        << "\"schemaVersion\":1,"
        << "\"totalMessageCount\":" << totalMessageCount << ','
        << "\"uniqueMessageCount\":" << records.size() << ','
        << "\"droppedMessageCount\":" << droppedMessageCount << ','
        << "\"records\":[";
    for (std::size_t index = 0; index < records.size(); ++index) {
        if (index != 0) out << ',';
        const auto& record = records[index];
        out << '{'
            << "\"severity\":" << json::quote(toString(record.value.severity)) << ','
            << "\"messageType\":" << json::quote(toString(record.value.type)) << ','
            << "\"messageIdNumber\":" << record.value.messageIdNumber << ','
            << "\"messageIdName\":" << json::quote(record.value.messageIdName) << ','
            << "\"message\":" << json::quote(record.value.message) << ','
            << "\"timestampEpochMs\":" << record.value.timestampEpochMs << ','
            << "\"fingerprint\":" << json::quote(record.fingerprint) << ','
            << "\"repeatCount\":" << record.repeatCount << ','
            << "\"objects\":[";
        for (std::size_t objectIndex = 0; objectIndex < record.value.objects.size(); ++objectIndex) {
            if (objectIndex != 0) out << ',';
            const auto& object = record.value.objects[objectIndex];
            out << '{'
                << "\"type\":" << json::quote(object.type) << ','
                << "\"name\":" << json::quote(object.name)
                << '}';
        }
        out << "]}";
    }
    out << "]}";
    return out.str();
}

std::string ValidationSnapshot::toHumanReadable() const {
    std::ostringstream out;
    out << "VULKAN VALIDATION\n"
        << "Total messages: " << totalMessageCount << '\n'
        << "Unique messages: " << records.size() << '\n'
        << "Dropped messages: " << droppedMessageCount << "\n\n";
    if (records.empty()) {
        out << "No validation messages collected. This does not prove that validation is enabled.\n";
        return out.str();
    }
    for (const auto& record : records) {
        out << '[' << toString(record.value.severity) << "] ["
            << toString(record.value.type) << "] "
            << record.value.messageIdName << " (" << record.value.messageIdNumber << ")\n"
            << record.value.message << '\n'
            << "fingerprint=" << record.fingerprint
            << " repeatCount=" << record.repeatCount << '\n';
        for (const auto& object : record.value.objects) {
            out << "  object type=" << object.type << " name=" << object.name << '\n';
        }
        out << '\n';
    }
    return out.str();
}

}  // namespace bncam::vulkan
