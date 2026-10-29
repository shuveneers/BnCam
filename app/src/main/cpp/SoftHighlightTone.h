#pragma once
#include "HighlightGamutProtectionV2.h"

namespace bncam::tone {
// Wide scene-linear shoulder leaves more display codes for cloud gradients. No
// unit clamp, spatial blur or fabricated detail: the remaining radiance stays ordered.
inline highlight::Rgb softHighlightTone(highlight::Rgb color) noexcept {
    for (int c=0;c<3;++c) color[c]=std::max(0.0f,color[c]);
    constexpr float start=.60f, prepStart=.44f, transition=1.f, width=.10f, slope=.12f, white=2.4f;
    float peak=std::max({color.r,color.g,color.b});
    const float x=std::min({color.r,color.g,color.b});
    const float offset=(x<0.08f ? x-6.25f*x*x : 0.04f)*
        highlight::smoothstep(prepStart,.60f,peak);
    for (int c=0;c<3;++c) color[c]-=offset;
    peak=std::max({color.r,color.g,color.b});
    if (peak<start) return color;
    const float join=transition+width, atJoin=.8f+width*(.25f+slope)*.5f;
    const float atWhite=atJoin+slope*(white-join);
    const float tail=std::max(0.0f,peak-white);
    const float t=peak-transition;
    const float mapped=peak<transition ? 1.f-.16f/(peak-.20f)
        : peak<join ? .8f+.25f*t-(.25f-slope)*t*t/(2*width)
        : peak<=white ? atJoin+slope*(peak-join)
        : atWhite+(1-atWhite)*tail/(tail+(1-atWhite)/slope);
    const float g=1.0f-1.0f/(0.15f*(peak-mapped)+1.0f);
    const highlight::Rgb source=color;
    for (int c=0;c<3;++c) color[c]=color[c]*(mapped/peak)*(1.0f-g)+mapped*g;
    // Above scene unity, uncertain chroma must not drive luminance through a
    // maximum-channel shoulder. Blend continuously into a luminance owner.
    const float y=highlight::luma(source), yt=y-transition, yTail=std::max(0.f,y-white);
    const float mappedY=y<start ? y : y<transition ? 1.f-.16f/(y-.20f)
        : y<join ? .8f+.25f*yt-(.25f-slope)*yt*yt/(2*width)
        : y<=white ? atJoin+slope*(y-join)
        : atWhite+(1-atWhite)*yTail/(yTail+(1-atWhite)/slope);
    const float yg=1.f-1.f/(.15f*std::max(0.f,y-mappedY)+1.f);
    const float authority=highlight::smoothstep(1.f,1.2f,peak);
    for(int c=0;c<3;++c) {
        const float value=source[c]*(mappedY/std::max(y,1e-8f))*(1-yg)+mappedY*yg;
        color[c]+=authority*(value-color[c]);
    }
    return color;
}
// Continuous grey-axis chroma compression. Both opponent components share one
// gain, preserving hue and luminance; approach the gamut boundary asymptotically.
inline highlight::Rgb softHighlightGamut(highlight::Rgb color) noexcept {
    if(!highlight::finiteRgb(color)) return {};
    const float y=highlight::luma(color);
    if(y<=0) return {};
    if(y>=1) return {1,1,1};
    const float high=std::max({color.r,color.g,color.b})-y;
    const float low=y-std::min({color.r,color.g,color.b});
    const float ratio=std::max(high/std::max(1e-8f,1-y),low/std::max(1e-8f,y));
    if(ratio<=.85f) return color;
    const float t=ratio-.85f;
    const float mapped=.85f+.15f*t/(.15f+t);
    for(int c=0;c<3;++c) color[c]=y+(color[c]-y)*(mapped/ratio);
    return color;
}
}
