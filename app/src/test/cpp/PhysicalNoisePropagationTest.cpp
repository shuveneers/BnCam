#include "../../main/cpp/PhysicalNoisePropagation.h"
#include <iostream>
using namespace bncam;

// Independent impulse reconstruction of the actual fixed Malvar stencils.
// Sum products at identical sample positions, not products of kernel norms.
std::array<double,3> reconstruct(int phase,int ix,int iy,bool malvar) {
    auto s=[&](int x,int y){return x==ix&&y==iy?1.0:0.0;};
    double c=s(0,0),h=s(-1,0)+s(1,0),v=s(0,-1)+s(0,1);
    double h2=s(-2,0)+s(2,0),v2=s(0,-2)+s(0,2);
    double diag=s(-1,-1)+s(-1,1)+s(1,-1)+s(1,1);
    double green=malvar?(4*c+2*(h+v)-h2-v2)/8:(h+v)/4;
    double opposite=malvar?(6*c+2*diag-1.5*(h2+v2))/8:diag/4;
    double ch=malvar?(5*c+4*h-h2-diag+.5*v2)/8:h/2;
    double cv=malvar?(5*c+4*v-v2-diag+.5*h2)/8:v/2;
    if(phase==0)return {c,green,opposite};
    if(phase==3)return {opposite,green,c};
    return phase==1?std::array<double,3>{ch,c,cv}:std::array<double,3>{cv,c,ch};
}
int main() {
    bool pass=true;
    for(bool malvar:{false,true})for(auto variance:{std::array<double,3>{1,1,1},{.001,.003,.002},{1e-10,2e-10,8e-10}}) {
        auto mode=malvar?spectra2::DemosaicModel::Malvar2004:spectra2::DemosaicModel::Bilinear;
        auto seed=spectra2::makeState("RAW","S/O","VALID",.8,spectra2::diagonalCovariance(variance[0],variance[1],variance[2]));
        auto prior=spectra2::propagateDemosaic(seed,mode);
        auto predicted=physical::propagateFixedDemosaic(seed,mode,prior);
        spectra2::Covariance3 expected;
        for(int phase=0;phase<4;++phase)for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x) {
            auto p=reconstruct(phase,x,y,malvar);
            int xx=(phase%2+x+4)%2,yy=(phase/2+y+4)%2;
            int color=xx==0&&yy==0?0:xx==1&&yy==1?2:1;
            for(int a=0;a<3;++a)for(int b=0;b<3;++b)expected.at(a,b)+=.25*p[a]*p[b]*variance[color];
        }
        for(int i=0;i<9;++i)pass &= std::abs(expected.values[i]-predicted.covariance.values[i])<
            1e-12*std::max(1e-20,std::abs(expected.values[i]));
        pass &= predicted.confidence==prior.confidence;
        if(malvar)pass &= predicted.varianceRG>prior.varianceRG&&predicted.varianceBG>prior.varianceBG;
        for(double confidence:{0.0001,.13,.774,1.0}) {
            prior.confidence=confidence;
            const auto p=physical::predictNoiseOnly(seed,mode,prior,true);
            pass &= p.valid && p.sourceConfidence==confidence && p.state.confidence==confidence;
        }
        pass &= !physical::predictNoiseOnly(seed,mode,prior,false).valid;
        pass &= physical::formatNoisePredictionOnly(seed,mode,prior,true,false).empty();
        const auto text=physical::formatNoisePredictionOnly(seed,mode,prior,true,true);
        pass &= text.find("appliedToPixels=false;filterBaseline=8aee2d2;valid=true")!=std::string::npos;
        for(double confidence:{0.0,-1.0,1.01,std::numeric_limits<double>::quiet_NaN(),
                               std::numeric_limits<double>::infinity()}) {
            auto invalid=prior;invalid.confidence=confidence;
            const auto p=physical::predictNoiseOnly(seed,mode,invalid,true);
            pass &= !p.valid && p.state.confidence==0;
            pass &= std::isnan(confidence)?std::isnan(p.sourceConfidence):p.sourceConfidence==confidence;
        }
        for(double bad:{0.0,-1.0,std::numeric_limits<double>::quiet_NaN(),
                        std::numeric_limits<double>::infinity()}) {
            auto invalid=seed;invalid.covariance.at(0,0)=bad;
            pass &= !physical::predictNoiseOnly(invalid,mode,prior,true).valid;
        }
        auto correlated=seed;correlated.covariance.at(0,1)=.01;
        pass &= !physical::predictNoiseOnly(correlated,mode,prior,true).valid;
        pass &= physical::propagateFixedDemosaic(correlated,mode,prior).covariance.values==prior.covariance.values;
        for(auto unsupported:{spectra2::DemosaicModel::Menon2007,spectra2::DemosaicModel::RcdInspired,
                              spectra2::DemosaicModel::AmazeInspired,spectra2::DemosaicModel::Unknown}) {
            pass &= !physical::predictNoiseOnly(seed,unsupported,prior,true).valid;
            pass &= physical::propagateFixedDemosaic(seed,unsupported,prior).covariance.values==prior.covariance.values;
        }
    }
    std::cout<<"fixed_kernel_impulse_covariance="<<pass<<'\n';return !pass;
}
