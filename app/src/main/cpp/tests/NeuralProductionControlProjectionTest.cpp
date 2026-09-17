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
    assert(near(natural.adaptiveResponse, 1.0f));
    assert(near(preset.adaptiveResponse, 1.0f));

    // Visible controls shape only residual components. Enabled Neural always has 100% master authority.
    const auto visibleNatural = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    assert(visibleNatural.enabled);
    assert(near(visibleNatural.noiseReduction, 1.0f));
    assert(near(visibleNatural.noiseReduction, preset.noiseReduction));
    assert(near(visibleNatural.lumaNoise, preset.lumaNoise));
    assert(near(visibleNatural.chromaNoise, preset.chromaNoise));
    assert(near(visibleNatural.detailProtection, preset.detailProtection));
    assert(near(visibleNatural.lowFrequencyCleanup, preset.lowFrequencyCleanup));
    assert(near(visibleNatural.adaptiveResponse, 1.0f));

    // Legacy profile master values are ignored. The On/Off mutation mode is the sole global gate.
    const auto legacyWeak = projectProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            -1.0f, 0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    const auto legacyStrong = projectProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            1.0f, 0.20f, 0.60f, 0.35f, 0.60f, 0.45f);
    assert(near(legacyWeak.noiseReduction, 1.0f));
    assert(near(legacyStrong.noiseReduction, 1.0f));

    // Legacy Adaptive Response is also ignored. Enabled Neural always owns full sigma/SNR adaptivity.
    const auto legacyAdaptiveOff = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.20f, 0.60f, 0.35f, 0.60f, 0.0f);
    const auto legacyAdaptiveOn = projectVisibleProfileControlsToNeural(
            NeuralProductionMutationMode::Auto,
            0.20f, 0.60f, 0.35f, 0.60f, 1.0f);
    assert(near(legacyAdaptiveOff.adaptiveResponse, 1.0f));
    assert(near(legacyAdaptiveOn.adaptiveResponse, 1.0f));

    // Off always wins even if explicit controls ask for mutation.
    NeuralProductionFrameEvidence off{};
    off.mutationMode = NeuralProductionMutationMode::Off;
    off.userControls = natural;
    off.userControlsPresent = true;
    const auto preparedOff = prepareNeuralProductionContext(off);
    assert(!preparedOff.controls.enabled);
    assert(near(preparedOff.controls.noiseReduction, 0.0f));
    assert(near(preparedOff.controls.lumaNoise, 0.0f));
    assert(near(preparedOff.controls.chromaNoise, 0.0f));
    assert(near(preparedOff.controls.detailProtection, 0.0f));
    assert(near(preparedOff.controls.lowFrequencyCleanup, 0.0f));
    assert(near(preparedOff.controls.adaptiveResponse, 0.0f));
    assert(preparedOff.structuralBypassReason == NeuralBypassReason::UserDisabled);

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
    assert(near(compat.adaptiveResponse, 1.0f));
    return 0;
}
