#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>

namespace bncam {

struct HeartbeatBuffer {
    char attemptId[64]{0};
    char currentStage[128]{"IDLE"};
    char currentSubstage[128]{"none"};
    int64_t entryTimestampMs = 0;
    char vulkanState[32]{"UNINITIALIZED"};
    char firstFailingStage[64]{"none"};
    uint32_t inFlightSubmissions = 0;
    int32_t lastVkResult = 0;
    uint64_t residentGeneration = 0;
};

class NativeStageHeartbeat {
public:
    static NativeStageHeartbeat& instance() {
        static NativeStageHeartbeat inst;
        return inst;
    }

    void update(
        const std::string& attemptId,
        const std::string& stage,
        const std::string& substage = "executing",
        const std::string& vulkanState = "READY",
        const std::string& firstFailingStage = "none",
        uint32_t inFlightSubmissions = 0,
        int32_t lastVkResult = 0,
        uint64_t residentGeneration = 0
    ) noexcept {
        const uint32_t writeIdx = (writeIndex_.load(std::memory_order_relaxed) + 1u) % 2u;
        auto& data = slots_[writeIdx];

        std::memset(&data, 0, sizeof(data));
        if (!attemptId.empty()) {
            std::strncpy(data.attemptId, attemptId.c_str(), sizeof(data.attemptId) - 1);
        }
        if (!stage.empty()) {
            std::strncpy(data.currentStage, stage.c_str(), sizeof(data.currentStage) - 1);
        }
        if (!substage.empty()) {
            std::strncpy(data.currentSubstage, substage.c_str(), sizeof(data.currentSubstage) - 1);
        }

        const auto now = std::chrono::steady_clock::now();
        data.entryTimestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();

        if (!vulkanState.empty()) {
            std::strncpy(data.vulkanState, vulkanState.c_str(), sizeof(data.vulkanState) - 1);
        }
        if (!firstFailingStage.empty()) {
            std::strncpy(data.firstFailingStage, firstFailingStage.c_str(), sizeof(data.firstFailingStage) - 1);
        }
        data.inFlightSubmissions = inFlightSubmissions;
        data.lastVkResult = lastVkResult;
        data.residentGeneration = residentGeneration;

        writeIndex_.store(writeIdx, std::memory_order_release);
    }

    std::string toJson() const noexcept {
        const uint32_t readIdx = writeIndex_.load(std::memory_order_acquire) % 2u;
        const auto& data = slots_[readIdx];
        const auto now = std::chrono::steady_clock::now();
        const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
        const int64_t elapsedMs = (data.entryTimestampMs > 0) ? (nowMs - data.entryTimestampMs) : 0;

        std::ostringstream ss;
        ss << "{\n"
           << "  \"attemptId\": \"" << data.attemptId << "\",\n"
           << "  \"currentNativeStage\": \"" << data.currentStage << "\",\n"
           << "  \"currentSubstage\": \"" << data.currentSubstage << "\",\n"
           << "  \"entryTimestampMs\": " << data.entryTimestampMs << ",\n"
           << "  \"elapsedStageMs\": " << elapsedMs << ",\n"
           << "  \"vulkanState\": \"" << data.vulkanState << "\",\n"
           << "  \"firstFailingStage\": \"" << data.firstFailingStage << "\",\n"
           << "  \"inFlightSubmissionCount\": " << data.inFlightSubmissions << ",\n"
           << "  \"lastVkResult\": " << data.lastVkResult << ",\n"
           << "  \"residentGeneration\": " << data.residentGeneration << "\n"
           << "}";
        return ss.str();
    }

private:
    std::atomic<uint32_t> writeIndex_{0};
    HeartbeatBuffer slots_[2];
};

} // namespace bncam
