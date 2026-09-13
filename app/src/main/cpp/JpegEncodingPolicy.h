#pragma once

#include <algorithm>
#include <vector>

#include <opencv2/imgcodecs.hpp>

namespace bncam {

// BnCam quality-first JPEG publication policy.
// Full-resolution RGB/chroma work produced by the ISP must not be discarded by
// the final JPEG encoder through 4:2:0 subsampling. OpenCV maps the sampling
// factor directly to the underlying JPEG codec.
//
// DELTA 0216: keep quality and 4:4:4 sampling unchanged, but do not run the
// optional second-pass Huffman table optimization. Huffman optimization is
// lossless entropy coding: disabling it changes file size/bitstream only, not
// JPEG DCT coefficients or decoded pixels, while avoiding substantial CPU time.
inline std::vector<int> jpeg444EncodingParameters(int quality) {
    const int boundedQuality = std::clamp(quality, 1, 100);
    return {
        cv::IMWRITE_JPEG_QUALITY, boundedQuality,
        cv::IMWRITE_JPEG_SAMPLING_FACTOR, cv::IMWRITE_JPEG_SAMPLING_FACTOR_444,
        cv::IMWRITE_JPEG_OPTIMIZE, 0
    };
}

} // namespace bncam
