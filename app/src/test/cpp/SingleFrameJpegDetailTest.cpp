#include "PerceptualDetailPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    namespace detail = bncam::perceptual_detail;
    namespace micro = bncam::profile_microdetail_transport;
    namespace edge = bncam::profile_edge_zipper_transport;
    const detail::NoiseEvidence noise{true, 0.012f, 0.85f};
    const detail::Controls positive{0.45f, 0.0f, micro::encodePair(0.40f, 0.22f),
                                    edge::encodePair(0.35f, 0.21f)};
    const auto multiFrame = detail::resolve(positive, noise, false);
    const auto refined = detail::resolve(positive, noise, true);
    assert(refined.enabled);
    assert(std::abs(refined.authority - multiFrame.authority * 0.92f) < 1.0e-6f);
    assert(std::abs(refined.detail - multiFrame.detail * 0.94f) < 1.0e-6f);
    assert(std::abs(edge::decodeEdge(refined.masking) -
                    edge::decodeEdge(multiFrame.masking) * 0.92f) < 0.001f);
    assert(edge::decodeAntiZipper(refined.masking) == edge::decodeAntiZipper(multiFrame.masking));
    assert(refined.radius == multiFrame.radius);
    assert(refined.minimumResidualSnr == multiFrame.minimumResidualSnr);
    assert(refined.minimumGradientSnr == multiFrame.minimumGradientSnr);
    assert(refined.hardHaloLimit < multiFrame.hardHaloLimit);

    detail::replayOriginalSingleFrameDetail = true;
    const auto baseline = detail::resolve(positive, noise, true);
    assert(baseline.authority == multiFrame.authority && baseline.detail == multiFrame.detail);
    assert(baseline.masking == multiFrame.masking && baseline.hardHaloLimit == multiFrame.hardHaloLimit);
    detail::replayOriginalSingleFrameDetail = false;

    const detail::Controls negative{-0.45f, 0.0f, micro::encodePair(-0.40f, -0.22f),
                                    edge::encodePair(-0.35f, 0.21f)};
    const auto negativeRefined = detail::resolve(negative, noise, true);
    const auto negativeBaseline = detail::resolve(negative, noise, false);
    assert(negativeRefined.authority == negativeBaseline.authority);
    assert(negativeRefined.detail == negativeBaseline.detail);
    assert(negativeRefined.masking == negativeBaseline.masking);
    assert(!detail::resolve({}, noise, true).enabled);
}
