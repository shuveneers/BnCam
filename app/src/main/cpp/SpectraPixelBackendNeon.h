#pragma once

#if defined(__ARM_NEON) || defined(__ARM_NEON__) || defined(BNCAM_SPECTRA_PIXEL_NEON_EMULATION)
#include <arm_neon.h>
#define BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE 1
#else
#define BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE 0
#endif

namespace bncam::spectra2::pixel_neon {

#if BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE

inline void opponentQuad(
        const float* interleavedRgb,
        float outputY[4],
        float outputRG[4],
        float outputBG[4],
        float outputSaturation[4]
) {
    const float32x4x3_t rgb = vld3q_f32(interleavedRgb);
    const float32x4_t r = rgb.val[0];
    const float32x4_t g = rgb.val[1];
    const float32x4_t b = rgb.val[2];

    const float32x4_t y = vaddq_f32(
            vaddq_f32(vmulq_n_f32(r, 0.2126f), vmulq_n_f32(g, 0.7152f)),
            vmulq_n_f32(b, 0.0722f)
    );
    const float32x4_t rg = vsubq_f32(r, g);
    const float32x4_t bg = vsubq_f32(b, g);
    const float32x4_t maximum = vmaxq_f32(vmaxq_f32(r, g), b);
    const float32x4_t minimum = vminq_f32(vminq_f32(r, g), b);
    const float32x4_t denominator = vmaxq_f32(maximum, vdupq_n_f32(0.02f));
#if defined(__aarch64__) || defined(BNCAM_SPECTRA_PIXEL_NEON_EMULATION)
    const float32x4_t saturation = vdivq_f32(vsubq_f32(maximum, minimum), denominator);
#else
    // The production target is arm64. This reciprocal refinement keeps the
    // header compilable for legacy ARM NEON builds without selecting that path.
    float32x4_t reciprocal = vrecpeq_f32(denominator);
    reciprocal = vmulq_f32(vrecpsq_f32(denominator, reciprocal), reciprocal);
    reciprocal = vmulq_f32(vrecpsq_f32(denominator, reciprocal), reciprocal);
    const float32x4_t saturation = vmulq_f32(vsubq_f32(maximum, minimum), reciprocal);
#endif

    vst1q_f32(outputY, y);
    vst1q_f32(outputRG, rg);
    vst1q_f32(outputBG, bg);
    vst1q_f32(outputSaturation, saturation);
}

#endif

} // namespace bncam::spectra2::pixel_neon
