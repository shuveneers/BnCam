#include "../../main/cpp/SingleFrameNoiseContext.h"
#include <array>
#include <cassert>
#include <string>

using namespace bncam;

int main() {
    const std::array<double,4> s{0.001,0.002,0.002,0.003};
    const std::array<double,4> o{0.00001,0.00002,0.00002,0.00003};
    const auto raw=spectra2::makeState("POST_FINALIZE","EXISTING","PROPAGATED",.9,
             spectra2::diagonalCovariance(.002,.004,.006));
    const auto rgb=spectra2::propagateDemosaic(raw,spectra2::DemosaicModel::Malvar2004);
    spectra2::ResidualObservation observed{};
    const auto phase2 = [&](spectra2::DemosaicModel algorithm,
                            bool hybrid,bool available,bool posterior) {
        return sf2::SingleFrameNoiseContext::observe(
            s,o,raw,rgb,observed,algorithm,hybrid,available,false,false,posterior);
    };
    const auto malvar=phase2(spectra2::DemosaicModel::Malvar2004,false,true,false);
    assert(malvar.demosaicSpatialNoise.kernelProxyAvailable);
    assert(!malvar.demosaicSpatialNoise.measured);
    assert(!malvar.demosaicSpatialNoise.inputIndependenceVerified);
    const auto activeReport=sf2::formatNoiseContextOnly(malvar);
    assert(activeReport.find("demosaicCorrelationStatus=FIXED_KERNEL_PROXY")!=std::string::npos);
    assert(activeReport.find("demosaicRhoHorizontal=")!=std::string::npos);
    assert(activeReport.find("demosaicCorrelationMeasured=false")!=std::string::npos);
    assert(activeReport.find("extraRawGainApplications=0;extraFusionGainApplications=0")!=std::string::npos);

    const auto adaptive=phase2(spectra2::DemosaicModel::AmazeInspired,false,true,false);
    const auto autoHybrid=phase2(spectra2::DemosaicModel::Malvar2004,true,true,false);
    const auto posterior=phase2(spectra2::DemosaicModel::Malvar2004,false,true,true);
    const auto missing=phase2(spectra2::DemosaicModel::Malvar2004,false,false,false);
    for(const auto& report:{
        sf2::formatNoiseContextOnly(adaptive),sf2::formatNoiseContextOnly(autoHybrid),
        sf2::formatNoiseContextOnly(posterior),sf2::formatNoiseContextOnly(missing)}) {
        assert(report.find("demosaicCorrelationProxyAvailable=false")!=std::string::npos);
        assert(report.find("demosaicCorrelationValues=UNAVAILABLE")!=std::string::npos);
        assert(report.find("demosaicRhoHorizontal=")==std::string::npos);
        assert(report.find("demosaicRhoVertical=")==std::string::npos);
    }
    assert(std::string(adaptive.demosaicSpatialNoise.status)
           =="ADAPTIVE_OR_UNSUPPORTED_KERNEL_UNKNOWN");
    assert(std::string(autoHybrid.demosaicSpatialNoise.status)
           =="AUTO_HYBRID_SPATIAL_WEIGHTS_UNKNOWN");
    assert(std::string(posterior.demosaicSpatialNoise.status)
           =="NEURAL_POSTERIOR_INPUT_SPATIAL_CORRELATION_UNKNOWN");
    assert(std::string(missing.demosaicSpatialNoise.status)
           =="PHYSICAL_NOISE_UNAVAILABLE");

    assert(malvar.rawVariance(0,.5)==adaptive.rawVariance(0,.5));
    assert(malvar.postDemosaic.varianceY==adaptive.postDemosaic.varianceY);
    return 0;
}
