#include "../../main/cpp/DemosaicNoiseCorrelation.h"
#include <cassert>
#include <cmath>
int main() {
    using namespace bncam;
    auto raw=spectra2::makeState("POST_RAW_FINALIZE","PHYSICAL","PROPAGATED",1,
        spectra2::diagonalCovariance(1.0,2.0,3.0));
    for(const auto model:{spectra2::DemosaicModel::Malvar2004,
                           spectra2::DemosaicModel::Bilinear}) {
        auto rgb=spectra2::propagateDemosaic(raw,model);
        auto c=sf2::predictSpatialCorrelation(raw,rgb,model,false);
        assert(c.known && c.confidence==rgb.confidence);
        assert(std::abs(c.rhoHorizontal)<=1 && std::abs(c.rhoVertical)<=1);
        assert(std::abs(c.rhoDiagonal)<=1);
        auto scaled=spectra2::makeState("POST_RAW_FINALIZE","PHYSICAL","PROPAGATED",1,
            spectra2::diagonalCovariance(10.,20.,30.));
        auto cs=sf2::predictSpatialCorrelation(scaled,rgb,model,false);
        assert(cs.known && std::abs(c.rhoHorizontal-cs.rhoHorizontal)<1e-12);
    }
    auto rgb=spectra2::propagateDemosaic(raw,spectra2::DemosaicModel::AmazeInspired);
    assert(!sf2::predictSpatialCorrelation(raw,rgb,spectra2::DemosaicModel::AmazeInspired,false).known);
    assert(!sf2::predictSpatialCorrelation(raw,rgb,spectra2::DemosaicModel::Malvar2004,true).known);
    raw.covariance.at(0,1)=.1;
    assert(!sf2::predictSpatialCorrelation(raw,rgb,spectra2::DemosaicModel::Malvar2004,false).known);
}
