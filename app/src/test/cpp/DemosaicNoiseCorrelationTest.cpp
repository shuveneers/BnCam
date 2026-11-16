#include "../../main/cpp/DemosaicNoiseCorrelation.h"
#include "../../main/cpp/PhysicalNoisePropagation.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <string>

using namespace bncam;

static bool approx(double lhs,double rhs,double relative=1e-11) {
    return std::abs(lhs-rhs) <= relative*std::max({1e-15,std::abs(lhs),std::abs(rhs)});
}

// Independent impulse reconstruction using a zero-valued synthetic mosaic and
// injecting one unit CFA sample. Does not invoke fixedDemosaicImpulseRgb().
static double referenceImpulseY(int phase,int sensorX,int sensorY,bool malvar) {
    const auto read=[=](int dx,int dy)->double {
        return (dx==sensorX && dy==sensorY) ? 1.0 : 0.0;
    };
    const double c=read(0,0);
    const double axial1=read(-1,0)+read(1,0)+read(0,-1)+read(0,1);
    const double axial2=read(-2,0)+read(2,0)+read(0,-2)+read(0,2);
    const double diagonals=read(-1,-1)+read(-1,1)+read(1,-1)+read(1,1);
    const double h1=read(-1,0)+read(1,0), v1=read(0,-1)+read(0,1);
    const double h2=read(-2,0)+read(2,0), v2=read(0,-2)+read(0,2);
    const double green=malvar
        ? 0.5*c+0.25*axial1-0.125*axial2
        : 0.25*axial1;
    const double diagonalCross=malvar
        ? 0.75*c+0.25*diagonals-0.1875*axial2
        : 0.25*diagonals;
    const double h=malvar
        ? 0.625*c+0.5*h1-0.125*h2-0.125*diagonals+0.0625*v2
        : 0.5*h1;
    const double v=malvar
        ? 0.625*c+0.5*v1-0.125*v2-0.125*diagonals+0.0625*h2
        : 0.5*v1;
    std::array<double,3> rgb{};
    switch(phase) {
    case 0: rgb={c,green,diagonalCross}; break;
    case 1: rgb={h,c,v}; break;
    case 2: rgb={v,c,h}; break;
    default: rgb={diagonalCross,green,c}; break;
    }
    return 0.2126*rgb[0]+0.7152*rgb[1]+0.0722*rgb[2];
}

static int refChannel(int centerPhase,int sx,int sy) {
    const bool xRed=(((centerPhase & 1)+sx+8)&1)==0;
    const bool yRed=((((centerPhase>>1)&1)+sy+8)&1)==0;
    return xRed&&yRed?0:(!xRed&&!yRed?2:1);
}

static std::array<double,2> independentOverlap(const std::array<double,3>& variances,
                                                  bool malvar,int dx,int dy) {
    double cov=0,var0=0;
    for(int phase=0;phase<4;++phase) {
        const int shifted=(((((phase>>1)&1)+dy+8)&1)<<1)|
                           (((phase&1)+dx+8)&1);
        for(int y=-3;y<=3;++y)for(int x=-3;x<=3;++x) {
            const double a=referenceImpulseY(phase,x,y,malvar);
            const double b=referenceImpulseY(shifted,x-dx,y-dy,malvar);
            const double weight=variances[static_cast<std::size_t>(refChannel(phase,x,y))];
            cov+=weight*a*b;
            var0+=weight*a*a;
        }
    }
    return {cov*0.25,var0*0.25};
}

int main() {
    for(const auto model:{spectra2::DemosaicModel::Malvar2004,
                          spectra2::DemosaicModel::Bilinear}) {
        const bool malvar=model==spectra2::DemosaicModel::Malvar2004;
        for(const auto var:{std::array<double,3>{1.0,1.0,1.0},
                            std::array<double,3>{1.0,3.0,10.0},
                            std::array<double,3>{0.0001,0.0003,0.00002}}) {
            auto raw=spectra2::makeState("POST_RAW_FINALIZE","TEST","PROPAGATED",0.79,
                            spectra2::diagonalCovariance(var[0],var[1],var[2]));
            auto oldPrediction=spectra2::propagateDemosaic(raw,model);
            auto computed=sf2::predictDemosaicSpatialNoise(
                            raw,oldPrediction,model,false,true,false);
            assert(computed.kernelProxyAvailable);
            assert(!computed.inputIndependenceVerified && !computed.measured);
            assert(std::string(computed.status).find("ASSUMED_NOT_MEASURED")!=std::string::npos);
            assert(approx(computed.existingPredictionConfidence,oldPrediction.confidence));
            assert(approx(computed.existingPredictionVarianceY,oldPrediction.varianceY));
            const auto horiz=independentOverlap(var,malvar,1,0);
            const auto vert=independentOverlap(var,malvar,0,1);
            const auto down=independentOverlap(var,malvar,1,1);
            const auto up=independentOverlap(var,malvar,1,-1);
            const auto referenceVariance=independentOverlap(var,malvar,0,0);
            assert(approx(computed.kernelVarianceY,referenceVariance[1],1e-10));
            assert(approx(computed.rhoHorizontal,horiz[0]/referenceVariance[1],1e-10));
            assert(approx(computed.rhoVertical,vert[0]/referenceVariance[1],1e-10));
            assert(approx(computed.rhoDiagonalDown,down[0]/referenceVariance[1],1e-10));
            assert(approx(computed.rhoDiagonalUp,up[0]/referenceVariance[1],1e-10));
            for(double rho:{computed.rhoHorizontal,computed.rhoVertical,
                            computed.rhoDiagonalDown,computed.rhoDiagonalUp}) {
                assert(std::isfinite(rho)&&std::abs(rho)<=1.0);
            }
            // Independent comparison with the existing phase-averaged
            // full RGB covariance impulse predictor (diagnostic only).
            const auto existingExact=physical::propagateFixedDemosaic(
                    raw,model,oldPrediction);
            assert(approx(computed.kernelVarianceY,existingExact.varianceY,1e-10));

            auto scaled=raw;
            for(double& n:scaled.covariance.values)n*=6.5;
            scaled.varianceY*=6.5;
            const auto again=sf2::predictDemosaicSpatialNoise(
                    scaled,oldPrediction,model,false,true,false);
            assert(again.kernelProxyAvailable);
            assert(approx(again.kernelVarianceY,computed.kernelVarianceY*6.5));
            assert(approx(again.rhoHorizontal,computed.rhoHorizontal));
            assert(approx(again.rhoVertical,computed.rhoVertical));
            assert(approx(again.rhoDiagonalDown,computed.rhoDiagonalDown));
            assert(approx(again.rhoDiagonalUp,computed.rhoDiagonalUp));
        }
    }
    const auto raw=spectra2::makeState("RAW","TEST","PROPAGATED",0.8,
                    spectra2::diagonalCovariance(0.1,0.2,0.3));
    const auto malvar=spectra2::propagateDemosaic(raw,spectra2::DemosaicModel::Malvar2004);
    auto unknown=[&](const spectra2::NoiseState& in,
                     const spectra2::NoiseState& out,
                     spectra2::DemosaicModel mode,bool hybrid,bool available,bool posterior) {
        const auto c=sf2::predictDemosaicSpatialNoise(in,out,mode,hybrid,available,posterior);
        assert(!c.kernelProxyAvailable);
        assert(c.kernelVarianceY==0);
        assert(!c.inputIndependenceVerified && !c.measured);
        assert(std::string(c.status)!="FIXED_KERNEL_PROXY_INDEPENDENT_CFA_ASSUMED_NOT_MEASURED");
        return c;
    };
    assert(std::string(unknown(raw,malvar,spectra2::DemosaicModel::Malvar2004,false,false,false).status)
          =="PHYSICAL_NOISE_UNAVAILABLE");
    assert(std::string(unknown(raw,malvar,spectra2::DemosaicModel::Malvar2004,true,true,false).status)
          =="AUTO_HYBRID_SPATIAL_WEIGHTS_UNKNOWN");
    for(const auto adaptive:{spectra2::DemosaicModel::AmazeInspired,
                             spectra2::DemosaicModel::RcdInspired,
                             spectra2::DemosaicModel::Menon2007,
                             spectra2::DemosaicModel::Unknown}) {
        assert(std::string(unknown(raw,malvar,adaptive,false,true,false).status)
                =="ADAPTIVE_OR_UNSUPPORTED_KERNEL_UNKNOWN");
    }
    assert(std::string(unknown(raw,malvar,spectra2::DemosaicModel::Malvar2004,false,true,true).status)
           =="NEURAL_POSTERIOR_INPUT_SPATIAL_CORRELATION_UNKNOWN");
    auto invalid=raw;
    invalid.covariance.at(0,1)=0.01;
    assert(std::string(unknown(invalid,malvar,spectra2::DemosaicModel::Malvar2004,false,true,false).status)
           =="INVALID_OR_NONDIAGONAL_INPUT_COVARIANCE");
    invalid=raw;
    invalid.covariance.at(0,0)=std::numeric_limits<double>::quiet_NaN();
    unknown(invalid,malvar,spectra2::DemosaicModel::Malvar2004,false,true,false);
    invalid=raw;
    invalid.covariance.at(2,2)=std::numeric_limits<double>::infinity();
    unknown(invalid,malvar,spectra2::DemosaicModel::Malvar2004,false,true,false);
    invalid=raw;
    invalid.confidence=0;
    unknown(invalid,malvar,spectra2::DemosaicModel::Malvar2004,false,true,false);
    auto badOutput=malvar;
    badOutput.varianceY=0;
    unknown(raw,badOutput,spectra2::DemosaicModel::Malvar2004,false,true,false);
    badOutput=malvar;badOutput.confidence=0;
    unknown(raw,badOutput,spectra2::DemosaicModel::Malvar2004,false,true,false);
    return 0;
}
