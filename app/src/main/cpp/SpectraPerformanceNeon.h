#pragma once

#if defined(__ARM_NEON) || defined(__ARM_NEON__) || defined(BNCAM_SPECTRA_NEON_EMULATION)
#include <arm_neon.h>
#define BNCAM_SPECTRA_NEON_AVAILABLE 1
#else
#define BNCAM_SPECTRA_NEON_AVAILABLE 0
#endif

namespace bncam::spectra2::performance_neon {

#if BNCAM_SPECTRA_NEON_AVAILABLE

inline void loadSignal2x2(
        const float* topRow,
        const float* bottomRow,
        int x,
        float output[4]
) {
    const float32x2_t top = vld1_f32(topRow + x);
    const float32x2_t bottom = vld1_f32(bottomRow + x);
    vst1_f32(output, top);
    vst1_f32(output + 2, bottom);
}

inline void residualDifferencePair(
        const float* current,
        const float* minus2,
        const float* plus2,
        int x,
        float output[2]
) {
    const float32x2_t center = vld1_f32(current + x);
    const float32x2_t vertical = vadd_f32(
            vld1_f32(minus2 + x),
            vld1_f32(plus2 + x)
    );
    const float32x2_t horizontal = vadd_f32(
            vld1_f32(current + x - 2),
            vld1_f32(current + x + 2)
    );
    const float32x2_t neighbourMean = vmul_n_f32(
            vadd_f32(vertical, horizontal),
            0.25f
    );
    vst1_f32(output, vsub_f32(center, neighbourMean));
}

inline float32x2_t greenReferencePair(
        const float* rowMinus1,
        const float* row,
        const float* rowPlus1,
        int x
) {
    const float32x2_t vertical = vadd_f32(
            vld1_f32(rowMinus1 + x),
            vld1_f32(rowPlus1 + x)
    );
    const float32x2_t horizontal = vadd_f32(
            vld1_f32(row + x - 1),
            vld1_f32(row + x + 1)
    );
    return vmul_n_f32(vadd_f32(vertical, horizontal), 0.25f);
}

inline float32x2_t chromaResidualPair(
        const float* rowMinus1,
        const float* row,
        const float* rowPlus1,
        int x
) {
    return vsub_f32(
            vld1_f32(row + x),
            greenReferencePair(rowMinus1, row, rowPlus1, x)
    );
}

inline void chromaDifferencePair(
        const float* rowMinus3,
        const float* rowMinus2,
        const float* rowMinus1,
        const float* row,
        const float* rowPlus1,
        const float* rowPlus2,
        const float* rowPlus3,
        int x,
        float output[2]
) {
    const float32x2_t center = chromaResidualPair(rowMinus1, row, rowPlus1, x);
    const float32x2_t left = chromaResidualPair(rowMinus1, row, rowPlus1, x - 2);
    const float32x2_t right = chromaResidualPair(rowMinus1, row, rowPlus1, x + 2);
    const float32x2_t above = chromaResidualPair(rowMinus3, rowMinus2, rowMinus1, x);
    const float32x2_t below = chromaResidualPair(rowPlus1, rowPlus2, rowPlus3, x);
    const float32x2_t neighbourMean = vmul_n_f32(
            vadd_f32(vadd_f32(left, right), vadd_f32(above, below)),
            0.25f
    );
    vst1_f32(output, vsub_f32(center, neighbourMean));
}

#endif

} // namespace bncam::spectra2::performance_neon
