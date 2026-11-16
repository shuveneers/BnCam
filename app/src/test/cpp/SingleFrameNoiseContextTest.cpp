#include "../../main/cpp/SingleFrameNoiseContext.h"
#include <cassert>
#include <cmath>
int main() {
    using namespace bncam;
    const std::array<double,4> s{.0001,.00012,.00011,.0002};
    const std::array<double,4> o{.00001,.000011,.000012,.00002};
    auto raw=spectra2::makeState("POST_RAW_FINALIZE","PHYSICAL_SO","PROPAGATED",.9,
        spectra2::diagonalCovariance(.00002,.00003,.00004));
    auto dem=spectra2::propagateDemosaic(raw,spectra2::DemosaicModel::Malvar2004);
    spectra2::ResidualObservation measured{};
    measured.acceptedSampleCount=4096;measured.robustVarianceY=.00003;
    const auto c=sf2::SingleFrameNoiseContext::observe(s,o,raw,dem,measured,
        spectra2::DemosaicModel::Malvar2004,false,true,true);
    assert(c.physicalAvailable && c.relativeSpatialSigmaAvailable);
    assert(c.postDemosaic.varianceY==dem.varianceY);
    assert(c.postFinalize.varianceY==raw.varianceY);
    assert(std::abs(c.rawVariance(0,.2)-.00003)<1e-12);
    assert(c.rawVariance(4,.2)==0);
    assert(c.rawVariance(0,NAN)==0);
    assert(c.diagnosticObservedToPredictedY()>0);
    measured.acceptedSampleCount=12;
    auto low=sf2::SingleFrameNoiseContext::observe(s,o,raw,dem,measured,
        spectra2::DemosaicModel::AmazeInspired,false,true,false);
    assert(low.observedPostDemosaicY==0);
    auto absent=sf2::SingleFrameNoiseContext::observe(s,o,raw,dem,measured,
        spectra2::DemosaicModel::Unknown,false,false,false);
    assert(!absent.physicalAvailable && absent.rawVariance(0,.2)==0);
    auto invalidS=s;invalidS[2]=NAN;
    assert(!sf2::SingleFrameNoiseContext::validSo(invalidS,o));
}
