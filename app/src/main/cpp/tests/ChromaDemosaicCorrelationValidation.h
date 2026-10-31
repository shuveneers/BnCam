#pragma once
#include "ChromaDetailValidation.h"
#include "../Demosaic.h"
#include <cstring>
namespace bncam::chroma::detailtest {
inline std::string runDemosaicNoise(Engine engine=test::reference) {
    bool pass=true;std::ostringstream report;report<<std::setprecision(10);
    for(int algorithm:{1,2})for(int cfa:{0,3})for(float variance:{.000004f,.000025f,.0004f}) {
        std::array<double,2> before{},after{};
        for(unsigned seed=1;seed<=4;++seed) {
            cv::Mat raw(test::H,test::W,CV_32FC1);
            std::mt19937 rng(seed);std::normal_distribution<float> normal(0,std::sqrt(variance));
            for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x)raw.at<float>(y,x)=.08f+normal(rng);
            auto rgbImage=algorithm==1?demosaicMalvar2004ToRgb32f(raw,cfa):demosaicAmazeInspiredToRgb32f(raw,cfa);
            Image input(test::W*test::H),truth(input.size(),Pixel{.08f,.08f,.08f});
            std::memcpy(input.data(),rgbImage.ptr<float>(),input.size()*sizeof(Pixel));
            auto n=spectra2::makeState("TEST","KNOWN_CFA_VARIANCE","VALID",1.,spectra2::diagonalCovariance(variance,variance,variance));
            n=spectra2::propagateDemosaic(n,algorithm==1?spectra2::DemosaicModel::Malvar2004:spectra2::DemosaicModel::AmazeInspired);
            auto m=fromNoiseState(n,true);m.adaptiveSpatialNoise=true;
            auto baseline=engine(input,m);m.protectCoherentDetail=true;auto candidate=engine(input,m);
            auto e0=mse(baseline,truth),e1=mse(candidate,truth);
            for(int c=0;c<2;++c){before[c]+=e0[c];after[c]+=e1[c];}
        }
        bool ok=after[0]<=before[0]*1.02&&after[1]<=before[1]*1.02;pass &= ok;
        report<<"correlatedDemosaic algorithm="<<algorithm<<" cfa="<<cfa<<" variance="<<variance
              <<" mseRatioRG="<<after[0]/before[0]<<" mseRatioBG="<<after[1]/before[1]<<" pass="<<ok<<'\n';
    }
    report<<"correlationPassed="<<(pass?"true":"false")<<'\n';return report.str();
}
inline std::string runFalseColour(Engine engine=test::reference) {
    bool pass=true;std::ostringstream report;report<<std::setprecision(10);
    for(int algorithm:{1,2})for(int pattern=0;pattern<4;++pattern) {
        cv::Mat raw(test::H,test::W,CV_32FC1);Image truth(test::W*test::H);
        for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x) {
            float feature=pattern==0?(x==32?1.f:0.f):(pattern==1?(x>=32?1.f:0.f):
                          (pattern==2?((x+y)%2?1.f:0.f):.5f*std::sin(2.1f*x)));
            float level=.08f+.02f*feature;raw.at<float>(y,x)=level;truth[y*test::W+x]={level,level,level};
        }
        auto d=algorithm==1?demosaicMalvar2004ToRgb32f(raw,0):demosaicAmazeInspiredToRgb32f(raw,0);
        Image input(truth.size());std::memcpy(input.data(),d.ptr<float>(),input.size()*sizeof(Pixel));
        Model m{.000025f,.0004f,.0004f,0};m.adaptiveSpatialNoise=true;
        auto baseline=engine(input,m);m.protectCoherentDetail=true;auto out=engine(input,m);
        auto a=mse(baseline,truth),b=mse(out,truth);
        if(algorithm==1&&pattern==0)for(int x=27;x<=37;++x) {
            auto p=opponent(input[16*test::W+x]),q=opponent(input[17*test::W+x]);
            report<<"achromaticColumn x="<<x<<" Y0="<<p[0]<<" Y1="<<q[0]<<" RG0="<<p[1]<<" RG1="<<q[1]
                  <<" baselineRG="<<opponent(baseline[16*test::W+x])[1]<<" candidateRG="<<opponent(out[16*test::W+x])[1]<<'\n';
        }
        bool ok=b[0]<=a[0]*1.02+1e-12&&b[1]<=a[1]*1.02+1e-12;pass &= ok;
        report<<"achromatic algorithm="<<algorithm<<" pattern="<<pattern<<" beforeRG="<<a[0]<<" afterRG="<<b[0]
              <<" beforeBG="<<a[1]<<" afterBG="<<b[1]<<" pass="<<ok<<'\n';
    }
    report<<"falseColourPassed="<<(pass?"true":"false")<<'\n';return report.str();
}
}
