#include "../../main/cpp/PhysicalLumaDenoise.h"
#include <cassert>
#include <iostream>
int main() {
    using namespace bncam::luma;
    // A fresh process defaults to Balanced, but ONLY for single-shot RAW,
    // Spectra OFF. No other capture receives an implicit luminance change.
    assert(selectedStage.load() == Stage::OFF);
    assert(!debugStageOverrideActive.load());
    assert(resolveStage(true, true) == Stage::GUIDED_V2_BALANCED);
    assert(resolveStage(false, true) == Stage::OFF);
    assert(resolveStage(true, false) == Stage::OFF);
    assert(resolveStage(false, false) == Stage::OFF);

    // Explicit developer fallback is fail-closed to OFF and remains bounded
    // to supported inputs. Reset restores the non-debug production decision.
    selectedStage.store(Stage::OFF);
    debugStageOverrideActive.store(true);
    assert(resolveStage(true, true) == Stage::OFF);
    selectedStage.store(Stage::GUIDED_V2_BALANCED);
    assert(resolveStage(true, true) == Stage::GUIDED_V2_BALANCED);
    selectedStage.store(Stage::LEGACY_REFERENCE);
    assert(resolveStage(false, true) == Stage::OFF);
    debugStageOverrideActive.store(false);
    selectedStage.store(Stage::OFF);
    assert(resolveStage(true, true) == Stage::GUIDED_V2_BALANCED);
    assert(!debugStageOverrideActive.load());
    std::cout << "SF2-FINAL default Balanced single-shot/Spectra OFF; protected fallback PASS\n";
    return 0;
}
