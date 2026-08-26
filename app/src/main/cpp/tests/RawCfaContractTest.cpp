#include "../RawCfaContract.h"

#include <cassert>
#include <cstring>

using namespace bncam::raw;

int main() {
    // Every Bayer arrangement must remain a valid Bayer contract.
    for (int pattern = CFA_RGGB; pattern <= CFA_BGGR; ++pattern) {
        for (int oy = 0; oy < 2; ++oy) {
            for (int ox = 0; ox < 2; ++ox) {
                const auto contract = resolveCfaContract(pattern, ox, oy);
                assert(contract.kind == RawCfaContractKind::STANDARD_BAYER);
                assert(contract.standardBayerMosaic);
                assert(contract.classicalBayerReconstructionAllowed);
                assert(isStandardBayerArrangement(contract.effectiveBayerPattern));
            }
        }
    }

    // Known phase checks from a RGGB sensor origin.
    assert(effectiveBayerPatternAtOrigin(CFA_RGGB, 0, 0) == CFA_RGGB);
    assert(effectiveBayerPatternAtOrigin(CFA_RGGB, 1, 0) == CFA_GRBG);
    assert(effectiveBayerPatternAtOrigin(CFA_RGGB, 0, 1) == CFA_GBRG);
    assert(effectiveBayerPatternAtOrigin(CFA_RGGB, 1, 1) == CFA_BGGR);

    // Non-Bayer Android arrangements must never collapse to RGGB.
    const auto rgb = resolveCfaContract(CFA_RGB, 0, 0);
    const auto mono = resolveCfaContract(CFA_MONO, 0, 0);
    const auto nir = resolveCfaContract(CFA_NIR, 0, 0);
    const auto unknown = resolveCfaContract(99, 0, 0);

    assert(rgb.kind == RawCfaContractKind::RGB_INTERLEAVED);
    assert(mono.kind == RawCfaContractKind::MONOCHROME);
    assert(nir.kind == RawCfaContractKind::NEAR_INFRARED);
    assert(unknown.kind == RawCfaContractKind::UNSUPPORTED_OR_UNKNOWN);

    assert(rgb.effectiveBayerPattern == CFA_UNSUPPORTED);
    assert(mono.effectiveBayerPattern == CFA_UNSUPPORTED);
    assert(nir.effectiveBayerPattern == CFA_UNSUPPORTED);
    assert(unknown.effectiveBayerPattern == CFA_UNSUPPORTED);

    assert(!rgb.classicalBayerReconstructionAllowed);
    assert(!mono.classicalBayerReconstructionAllowed);
    assert(!nir.classicalBayerReconstructionAllowed);
    assert(!unknown.classicalBayerReconstructionAllowed);

    assert(std::strcmp(cfaArrangementName(CFA_RGB), "RGB") == 0);
    assert(std::strcmp(cfaArrangementName(CFA_MONO), "MONO") == 0);
    assert(std::strcmp(cfaArrangementName(CFA_NIR), "NIR") == 0);
    assert(std::strcmp(cfaArrangementName(99), "UNKNOWN") == 0);
    return 0;
}
