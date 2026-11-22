#include "../../main/cpp/SingleFrameIntegrationQualification.h"
#include <cassert>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>

int main() {
    namespace q=bncam::sf2::qualification;
    using bncam::luma::Stage;
    const auto input=q::fixture();
    assert(input.size()==std::size_t(q::kWidth*q::kHeight));
    const auto off=q::cpuReference(input,Stage::OFF);
    assert(off==input);
    const auto legacy=q::cpuReference(input,Stage::LEGACY_REFERENCE);
    const auto fine=q::cpuReference(input,Stage::GUIDED_V2);
    const auto balanced=q::cpuReference(input,Stage::GUIDED_V2_BALANCED);
    const auto combined=q::cpuReference(input,Stage::GUIDED_V2_FINE_COARSE);
    const auto unknown=q::cpuReference(input,Stage::GUIDED_V2_CONFIDENCE,false);
    const auto proxy=q::cpuReference(input,Stage::GUIDED_V2_CONFIDENCE,true);
    const auto bogus=q::cpuReference(input,static_cast<Stage>(99));
    assert(bogus.empty());
    for(const auto& image:{legacy,fine,balanced,combined,unknown,proxy}){
        assert(image.size()==input.size());
        for(std::size_t i=0;i<image.size();++i) {
            assert(bncam::chroma::finite(image[i]));
            const auto before=bncam::chroma::opponent(input[i]);
            const auto after=bncam::chroma::opponent(image[i]);
            assert(std::abs(before[1]-after[1])<2e-6f);
            assert(std::abs(before[2]-after[2])<2e-6f);
        }
    }
    // The gate is strictly a multiplier 0..1 of the pre-gated candidate.
    float maxFine=0.f,maxCombined=0.f,maxGated=0.f;
    std::size_t gated=0;
    for(std::size_t i=0;i<input.size();++i){
        const float f=fine[i][0]-off[i][0];
        const float c=combined[i][0]-off[i][0];
        const float g=unknown[i][0]-off[i][0];
        const float p=proxy[i][0]-off[i][0];
        assert(std::abs(g)<=std::abs(c)+3e-7f);
        assert(std::abs(p)<=std::abs(c)+3e-7f);
        if(std::abs(c)>1.e-8f){
            assert(g*c>=-1e-9f);
            assert(p*c>=-1e-9f);
        }
        if(std::abs(c-g)>1.e-8f)++gated;
        maxFine=std::max(maxFine,std::abs(f));
        maxCombined=std::max(maxCombined,std::abs(c));
        maxGated=std::max(maxGated,std::abs(g));
    }
    assert(maxFine>0.f && maxCombined>0.f);
    assert(gated>0);

    // Comparison contract rejects missing, NaN, and truncated GPU frame buffers.
    std::vector<float> packed(input.size()*3u);
    for(std::size_t i=0;i<input.size();++i)
       for(int c=0;c<3;++c)packed[i*3u+c]=combined[i][c];
    auto same=q::compare(combined,packed);
    assert(same.valid&&same.finite&&same.maxAbsoluteRgb==0.f&&same.changedPixels==0);
    packed[3]+=1.5e-4f;
    auto drift=q::compare(combined,packed);
    assert(drift.valid&&drift.maxAbsoluteRgb>q::kGpuParityTolerance);
    packed[3]=std::numeric_limits<float>::quiet_NaN();
    assert(!q::compare(combined,packed).valid);
    packed.resize(10);
    assert(!q::compare(combined,packed).valid);
    std::cout<<"fixture="<<input.size()<<";gatedPixels="<<gated
             <<";maxFineDelta="<<maxFine<<";maxCombinedDelta="<<maxCombined
             <<";maxGatedDelta="<<maxGated<<"\n";
    return 0;
}
