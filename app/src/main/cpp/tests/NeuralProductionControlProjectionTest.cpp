#include "SpectraNeuralProductionPolicy.h"
#include "SpectraNeuralResidualControl.h"

#include <cassert>
#include <cmath>

using namespace bncam::spectra::neural;

static bool near(float a, float b, float eps = 1.0e-6f) { return std::abs(a-b) <= eps; }

int main() {
    // Existing Natural profile values project exactly to the transparent Phase-6 Natural vector.
    const auto natural = projectProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.0f, 0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    const auto preset = neuralCharacterControls(NeuralCharacterPreset::Natural);
    assert(natural.enabled);
    assert(near(natural.noiseReduction, preset.noiseReduction));
    assert(near(natural.lumaNoise, preset.lumaNoise));
    assert(near(natural.chromaNoise, preset.chromaNoise));
    assert(near(natural.detailProtection, preset.detailProtection));
    assert(near(natural.lowFrequencyCleanup, preset.lowFrequencyCleanup));
    assert(near(natural.adaptiveResponse, preset.adaptiveResponse));

    // Phase-6 visible controls use direct unit master/adaptive values and still match Natural.
    const auto visibleNatural = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.70f, 0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    assert(visibleNatural.enabled);
    assert(near(visibleNatural.noiseReduction, preset.noiseReduction));
    assert(near(visibleNatural.lumaNoise, preset.lumaNoise));
    assert(near(visibleNatural.chromaNoise, preset.chromaNoise));
    assert(near(visibleNatural.detailProtection, preset.detailProtection));
    assert(near(visibleNatural.lowFrequencyCleanup, preset.lowFrequencyCleanup));
    assert(near(visibleNatural.adaptiveResponse, preset.adaptiveResponse));

    // Direct visible master has exact endpoints; zero authority is not remapped through legacy 0.70.
    const auto visibleZero = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.0f, 0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    assert(near(visibleZero.noiseReduction, 0.0f));

    // Migration master has exact endpoints and old neutral maps to Natural.
    assert(near(legacySpectraMasterToUnit(-1.0f), 0.0f));
    assert(near(legacySpectraMasterToUnit(0.0f), 0.70f));
    assert(near(legacySpectraMasterToUnit(1.0f), 1.0f));

    // Off always wins even if explicit controls ask for mutation.
    NeuralProductionFrameEvidence off{};
    off.mutationMode = NeuralProductionMutationMode::Off;
    off.userControls = natural;
    off.userControlsPresent = true;
    const auto preparedOff = prepareNeuralProductionContext(off);
    assert(!preparedOff.controls.enabled);
    assert(near(preparedOff.controls.noiseReduction, 0.0f));
    assert(preparedOff.structuralBypassReason == NeuralBypassReason::NeuralDisabled);

    // Zero master is an exact pre-inference bypass.
    NeuralProductionFrameEvidence zero{};
    zero.mutationMode = NeuralProductionMutationMode::Auto;
    zero.userControls = natural;
    zero.userControls.noiseReduction = 0.0f;
    zero.userControlsPresent = true;
    const auto preparedZero = prepareNeuralProductionContext(zero);
    assert(preparedZero.structuralBypassReason == NeuralBypassReason::ZeroAuthority);

    // Old callers still receive full Phase-5 component authority.
    const auto compat = phase5ProductionControls(NeuralProductionMutationMode::Auto);
    assert(near(compat.noiseReduction, 1.0f));
    assert(near(compat.lumaNoise, 1.0f));
    assert(near(compat.chromaNoise, 1.0f));
    assert(near(compat.detailProtection, 0.0f));
    assert(near(compat.lowFrequencyCleanup, 1.0f));
    assert(near(compat.adaptiveResponse, 0.0f));
    return 0;
}
