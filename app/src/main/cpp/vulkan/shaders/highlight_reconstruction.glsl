// Per-channel source-RAW provenance; packed samples stay in their physical CFA positions.
vec3 highlightCellReliability(ivec2 cell) {
    ivec2 size=ivec2((pc.frameWidth+1u)/2u,(pc.frameHeight+1u)/2u);
    cell=clamp(cell,ivec2(0),size-1);
    vec4 q=unpackUnorm4x8(floatBitsToUint(inputMosaic[uint(cell.y*size.x+cell.x)]));
    vec3 outQ=vec3(0.0);
    for(int i=0;i<4;++i) {
        uint c=cfaColorAt(pc.cfaPattern,i%2,i/2);
        outQ[c]+=q[i]*(c==1u ? 0.5 : 1.0);
    }
    return outQ;
}
vec3 highlightChannelReliability(ivec2 pixel) {
    vec2 cell=vec2(pixel)*0.5-0.25; ivec2 at=ivec2(floor(cell));
    vec4 wx=highlightWeights(fract(cell.x)),wy=highlightWeights(fract(cell.y));
    vec3 q=vec3(0.0);
    for(int j=0;j<4;++j)for(int i=0;i<4;++i)
        q+=wx[i]*wy[j]*highlightCellReliability(at+ivec2(i-1,j-1));
    return clamp(q,0.0,1.0);
}
vec3 gradientHighlightAt(ivec2 pixel) {
    vec3 center=residentRgbAt(pixel.x,pixel.y);
    vec3 q=highlightChannelReliability(pixel);
    if(min(q.r,min(q.g,q.b))>0.999999) return center;
    int guide=1;
    for(int k=0;k<3;k+=2) if(q[k]>q[guide]) guide=k;
    if(center[guide]<0.02) return center;
    vec3 gains=vec3(pc.wbR,pc.wbG,pc.wbB);
    vec3 balanced=center*gains;
    float neutralFloor=max(balanced.r,max(balanced.g,balanced.b));
    if(q[guide]<=1e-6) return vec3(neutralFloor)/gains;
    const ivec2 directions[8]=ivec2[8](ivec2(1,0),ivec2(-1,0),ivec2(0,1),ivec2(0,-1),
        ivec2(1,1),ivec2(1,-1),ivec2(-1,1),ivec2(-1,-1));
    const int radii[4]=int[4](2,8,32,128);
    vec3 outRgb=center;
    for(int c=0;c<3;++c) {
        if(c==guide || q[c]>0.999999) continue;
        float sum=0.0, weights=0.0;
        for(int s=0;s<4;++s)for(int d=0;d<8;++d) {
            ivec2 at=clamp(pixel+directions[d]*radii[s],ivec2(0),ivec2(pc.frameWidth,pc.frameHeight)-1);
            vec3 n=residentRgbAt(at.x,at.y), nq=highlightCellReliability(at/2);
            if(n[guide]<0.02 || n[c]<0.01) continue;
            float edge=log2(max(0.001,n[guide]/center[guide])), hue=0.0;
            for(int k=0;k<3;++k) if(k!=guide && q[k]>0.75 && nq[k]>0.75) {
                float a=center[k]/max(0.001,center[guide]), b=n[k]/max(0.001,n[guide]);
                hue=max(hue,abs(log2(max(0.001,a)/max(0.001,b))));
            }
            float w=nq[c]*nq[guide]*exp(-1.5*edge*edge-8.0*hue*hue);
            sum+=w*clamp(log2(n[c]/n[guide]),-4.0,4.0); weights+=w;
        }
        float neutral=log2(max(0.001,gains[guide])/max(0.001,gains[c]));
        float other=0.0;for(int k=0;k<3;++k)if(k!=guide)other=max(other,q[k]);
        float support=weights/(weights+0.5)*other;
        float ratio=support*(sum/max(weights,1e-8))+(1.0-support)*neutral;
        float estimate=center[guide]*exp2(ratio), e=estimate-center[c];
        estimate=center[c]+0.5*(e+sqrt(e*e+1e-6));
        estimate=min(estimate,max(0.02,center[c])*4.0);
        outRgb[c]=center[c]+(estimate-center[c])*(1.0-q[c]);
    }
    float fullLoss=1.0-smoothstep(0.0,0.65,q[guide]);
    return mix(outRgb,vec3(neutralFloor)/gains,fullLoss);
}
