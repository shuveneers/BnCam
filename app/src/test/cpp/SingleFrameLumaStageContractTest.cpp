#include "../../main/cpp/PhysicalLumaDenoise.h"
#include <cassert>
int main() {
    using namespace bncam::luma;
    assert(selectedStage.load() == Stage::OFF);
    assert(!legacyAuthorized(Stage::OFF));
    assert(!legacyAuthorized(Stage::GUIDED_V2));
    assert(!legacyAuthorized(Stage::GUIDED_V2_UNGATED));
    assert(legacyAuthorized(Stage::LEGACY_REFERENCE));
    selectedStage.store(Stage::LEGACY_REFERENCE);
    assert(legacyAuthorized(selectedStage.load()));
    selectedStage.store(Stage::OFF);
    assert(selectedStage.load() == Stage::OFF);
}
