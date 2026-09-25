#pragma once
#include <algorithm>
#include <cstdint>
#include <limits>
namespace bncam::vulkan::neural {
constexpr std::uint32_t kNeuralChannelPack = 4u;
constexpr std::uint32_t kNeuralPackedVecBytes = 8u; // four FP16 values
inline constexpr std::uint32_t ceilDiv(std::uint32_t a,std::uint32_t b) noexcept{return b?((a+b-1u)/b):0u;}
inline constexpr std::uint32_t alignUp(std::uint32_t v,std::uint32_t a) noexcept{return a?ceilDiv(v,a)*a:v;}
struct NeuralTensorShape {std::uint32_t width=0,height=0,channels=0; bool valid()const noexcept{return width&&height&&channels;} std::uint32_t c4()const noexcept{return ceilDiv(channels,4u);} std::uint64_t bytesFp16C4()const noexcept{return std::uint64_t(width)*height*c4()*kNeuralPackedVecBytes;}};
struct NeuralTileGeometry {
 std::uint32_t fullWidth=0,fullHeight=0,paddedWidth=0,paddedHeight=0,innerWidth=0,innerHeight=0,halo=0;
 bool valid()const noexcept{return fullWidth&&fullHeight&&innerWidth&&innerHeight&&paddedWidth>=fullWidth&&paddedHeight>=fullHeight;}
 std::uint32_t tileInputWidth()const noexcept{return innerWidth+2u*halo;} std::uint32_t tileInputHeight()const noexcept{return innerHeight+2u*halo;}
};
struct NeuralTileRegion {
 std::uint32_t centerX=0,centerY=0,centerWidth=0,centerHeight=0;
 std::uint32_t inputX=0,inputY=0,inputWidth=0,inputHeight=0;
 std::uint32_t validX=0,validY=0;
};
inline NeuralTileGeometry makeTileGeometry(std::uint32_t w,std::uint32_t h,std::uint32_t inner,std::uint32_t halo) noexcept {NeuralTileGeometry g{};g.fullWidth=w;g.fullHeight=h;g.paddedWidth=alignUp(w,8u);g.paddedHeight=alignUp(h,8u);g.innerWidth=inner;g.innerHeight=inner;g.halo=halo;return g;}
inline std::uint32_t neuralTileCount(const NeuralTileGeometry& g) noexcept{return g.valid()?ceilDiv(g.paddedWidth,g.innerWidth)*ceilDiv(g.paddedHeight,g.innerHeight):0u;}
inline NeuralTileRegion neuralTileAt(const NeuralTileGeometry& g,std::uint32_t index) noexcept {
 NeuralTileRegion r{};if(!g.valid())return r;
 const auto nx=ceilDiv(g.paddedWidth,g.innerWidth);const auto x=index%nx,y=index/nx;
 r.centerX=x*g.innerWidth;r.centerY=y*g.innerHeight;
 r.centerWidth=std::min(g.innerWidth,g.paddedWidth-r.centerX);
 r.centerHeight=std::min(g.innerHeight,g.paddedHeight-r.centerY);
 // Edge tiles terminate at the actual padded image domain. This is required
 // for exact full-frame parity when convolution biases exist: fictitious
 // feature positions beyond the image boundary must never be materialized.
 r.inputX=r.centerX>g.halo?r.centerX-g.halo:0u;
 r.inputY=r.centerY>g.halo?r.centerY-g.halo:0u;
 const std::uint32_t endX=std::min(g.paddedWidth,r.centerX+r.centerWidth+g.halo);
 const std::uint32_t endY=std::min(g.paddedHeight,r.centerY+r.centerHeight+g.halo);
 r.inputWidth=endX-r.inputX;r.inputHeight=endY-r.inputY;
 r.validX=r.centerX-r.inputX;r.validY=r.centerY-r.inputY;
 return r;
}
} // namespace bncam::vulkan::neural
