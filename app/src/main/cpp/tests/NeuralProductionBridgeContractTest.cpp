#include "../SpectraCoreSnapshot.h"
#include <array>
#include <cassert>
#include <iostream>

using namespace bncam::spectra::neural;

int main(){
    const std::array<int,4> patterns{{bncam::raw::CFA_RGGB,bncam::raw::CFA_GRBG,bncam::raw::CFA_GBRG,bncam::raw::CFA_BGGR}};
    for(int pattern:patterns){
        const auto cfa=resolveCanonicalBayerPack(pattern,0,0);
        assert(cfa.supportsExtent(8,6));
        std::array<float,48> mosaic{};for(std::size_t i=0;i<mosaic.size();++i)mosaic[i]=static_cast<float>(i);
        std::array<float,48> roundtrip{};std::array<float,48> packed{};
        const int pw=4,ph=3;
        for(int py=0;py<ph;++py)for(int px=0;px<pw;++px)for(int c=0;c<4;++c){auto o=cfa.sourceOffsets[static_cast<std::size_t>(c)];packed[(py*pw+px)*4+c]=mosaic[(py*2+o.y)*8+(px*2+o.x)];}
        for(int py=0;py<ph;++py)for(int px=0;px<pw;++px)for(int c=0;c<4;++c){auto o=cfa.sourceOffsets[static_cast<std::size_t>(c)];roundtrip[(py*2+o.y)*8+(px*2+o.x)]=packed[(py*pw+px)*4+c];}
        assert(roundtrip==mosaic);
    }
    std::cout<<"NeuralProductionBridgeContractTest PASS\n";return 0;
}
