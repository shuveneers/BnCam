#include "VulkanRuntimeState.h"
#include "VulkanValidationCollector.h"

#include <cassert>
#include <iostream>
#include <string>

using namespace bncam::vulkan;

int main() {
    static_assert(static_cast<int>(RuntimeState::UNINITIALIZED) == 0);
    static_assert(static_cast<int>(RuntimeState::READY) == 2);
    static_assert(static_cast<int>(RuntimeState::DESTROYED) == 6);

    ValidationCollector collector(2, 80, 2);
    ValidationMessageInput duplicate;
    duplicate.severity = ValidationSeverity::ERROR;
    duplicate.type = ValidationMessageType::VALIDATION;
    duplicate.messageIdNumber = 7;
    duplicate.messageIdName = "VUID-test";
    duplicate.message = std::string(200, 'x');
    duplicate.objects = {{"BUFFER", "input"}, {"IMAGE", "output"}, {"EXTRA", "trimmed"}};

    collector.record(duplicate);
    collector.record(duplicate);

    ValidationMessageInput second;
    second.messageIdNumber = 8;
    second.message = "second";
    collector.record(second);

    ValidationMessageInput dropped;
    dropped.messageIdNumber = 9;
    dropped.message = "must be dropped because unique capacity is two";
    collector.record(dropped);

    const auto snapshot = collector.snapshot();
    assert(snapshot.records.size() == 2);
    assert(snapshot.records[0].repeatCount == 2);
    assert(snapshot.records[0].value.message.size() == 80);
    assert(snapshot.records[0].value.objects.size() == 2);
    assert(snapshot.totalMessageCount == 4);
    assert(snapshot.droppedMessageCount == 1);
    const std::string json = snapshot.toJson();
    assert(json.find("\\\"repeatCount\\\":2") == std::string::npos); // JSON is not double escaped.
    assert(json.find("\"repeatCount\":2") != std::string::npos);
    assert(json.find("\"droppedMessageCount\":1") != std::string::npos);
    assert(snapshot.toHumanReadable().find("Dropped messages: 1") != std::string::npos);

    collector.clear();
    assert(collector.snapshot().records.empty());
    std::cout << "Vulkan validation collector host test PASS\n";
    return 0;
}
