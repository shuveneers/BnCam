#include "../src/main/cpp/SensorColorScienceV2.h"

#include <cmath>
#include <iostream>

static int failures = 0;
static int passes = 0;

static void check(const char* name, bool ok) {
    if (ok) {
        ++passes;
        std::cout << "PASS: " << name << "\n";
    } else {
        ++failures;
        std::cout << "FAIL: " << name << "\n";
    }
}

int main() {
    const float identity[9] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    const auto identityAudit = bncam::color::auditLinearSrgbMatrix(identity);
    check("identity finite", identityAudit.finite);
    check("identity determinant", std::abs(identityAudit.determinant - 1.0f) < 1.0e-6f);
    check("identity neutral spread zero", identityAudit.neutralAxisSpread < 1.0e-6f);

    const float unequalRows[9] = {
        1.2f, -0.2f, 0.1f,
       -0.1f,  1.0f, 0.0f,
        0.0f, -0.1f, 0.9f
    };
    const auto unequalAudit = bncam::color::auditLinearSrgbMatrix(unequalRows);
    check("nontrivial matrix finite", unequalAudit.finite);
    check("row-sum difference observed not normalized away", unequalAudit.neutralAxisSpread > 0.05f);

    const auto neutralScene = bncam::color::auditPrePresentationSceneMean(0.25, 0.25, 0.25);
    check("neutral scene finite", neutralScene.finite);
    check("neutral scene spread zero", neutralScene.normalizedRgbSpread < 1.0e-12);

    const auto chromaticScene = bncam::color::auditPrePresentationSceneMean(0.4, 0.2, 0.1);
    check("chromatic scene finite", chromaticScene.finite);
    check("chromatic scene spread detected", chromaticScene.normalizedRgbSpread > 0.5);

    const float nonFinite[9] = {
        NAN, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    check("nonfinite matrix rejected", !bncam::color::auditLinearSrgbMatrix(nonFinite).finite);

    if (failures != 0) {
        std::cerr << "PHASE8_NEUTRAL_VALIDATION_CONTRACT_FAIL=" << failures << "\n";
        return 1;
    }
    std::cout << "PHASE8_NEUTRAL_VALIDATION_CONTRACT_PASS=" << passes << "\n";
    return 0;
}
