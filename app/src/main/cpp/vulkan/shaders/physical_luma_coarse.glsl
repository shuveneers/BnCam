// SF2-D05: compact quarter-resolution luma bandpass, stages 14/15.
// Binding 8 is one vec4 per coarse cell: Y, independent-reference variance,
// Cauchy upper variance bound, validity. Binding 9 is one float correction.
// Both buffers stay GPU-resident. No image readback or tone curve is introduced.
// Assumption caveat: post-demosaic sites are NOT proven independent.
uvec2 coarseDimensions() {
    return (uvec2(pc.frameWidth,pc.frameHeight)+uvec2(3u))/4u;
}

uint coarseIndex(ivec2 xy) {
    uvec2 shape=coarseDimensions();
    ivec2 p=clamp(xy,ivec2(0),ivec2(shape)-ivec2(1));
    return uint(p.y)*shape.x+uint(p.x);
}
vec4 coarseRead(ivec2 xy) {
    return coarseLuma[coarseIndex(xy)];
}
void coarseDownsampleAt(uvec2 gid) {
    uvec2 shape=coarseDimensions();
    if(gid.x>=shape.x||gid.y>=shape.y)return;
    uint index=gid.y*shape.x+gid.x;
    if(pc.cfaEvidence1.y<=0.0||!(pc.padding0>0.0)) {
        coarseLuma[index]=vec4(0.0);
        return;
    }
    float taps[4]=float[4](0.125,0.375,0.375,0.125);
    float mean=0.0,varIndependent=0.0,sigmaBound=0.0;
    for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx) {
        ivec2 xy=min(ivec2(gid)*4+ivec2(dx,dy),ivec2(pc.frameWidth,pc.frameHeight)-ivec2(1));
        vec3 rgb=physicalChromaRead(xy);
        float s=physicalChromaShape(xy);
        float y=opponentAtRgb(rgb).x;
        float v=pc.padding0*s*s;
        if(!physicalChromaFinite(rgb)||!(s>0.0)||isnan(s)||isinf(s)||
           isnan(y)||isinf(y)||!(v>1.e-15)||isnan(v)||isinf(v)) {
            coarseLuma[index]=vec4(0.0);
            return;
        }
        float w=taps[dx]*taps[dy];
        mean+=w*y;
        varIndependent+=w*w*v;
        sigmaBound+=w*sqrt(v);
    }
    float varianceUpper=sigmaBound*sigmaBound;
    if(!(varIndependent>1.e-15)||isnan(mean)||isinf(mean)||
       isnan(varIndependent)||isinf(varIndependent)||
       isnan(varianceUpper)||isinf(varianceUpper)||
       varianceUpper<varIndependent) {
        coarseLuma[index]=vec4(0.0);
        return;
    }
    coarseLuma[index]=vec4(mean,varIndependent,varianceUpper,1.0);
}

void coarseFilterAt(uvec2 gid) {
    uvec2 dims=coarseDimensions();
    if(gid.x>=dims.x||gid.y>=dims.y)return;
    uint index=gid.y*dims.x+gid.x;
    vec4 center=coarseRead(ivec2(gid));
    if(center.w<0.5||!(center.y>1.e-15)||!(center.z>=center.y)||
       isnan(center.x)||isinf(center.x)) {
        coarseDelta[index]=0.0;
        return;
    }
    float variance=center.y;
    float sigma=sqrt(variance);
    float radiusVariance=max(4.0*variance,1.e-12);
    float sum=0.0,total=0.0;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx) {
        if(dx==0&&dy==0)continue;
        vec4 other=coarseRead(ivec2(gid)+ivec2(dx,dy));
        if(other.w<0.5||isnan(other.x)||isinf(other.x)) {
            coarseDelta[index]=0.0;
            return;
        }
        float diff=other.x-center.x;
        float spatial=(dx==0||dy==0)?2.0:1.0;
        float weight=spatial*exp(-(diff*diff)/radiusVariance);
        total+=weight;
        sum+=weight*other.x;
    }
    if(!(total>1.e-10)||isnan(total)||isinf(total)||isnan(sum)||isinf(sum)) {
        coarseDelta[index]=0.0;
        return;
    }
    float left=coarseRead(ivec2(gid)+ivec2(-1,0)).x;
    float right=coarseRead(ivec2(gid)+ivec2(1,0)).x;
    float top=coarseRead(ivec2(gid)+ivec2(0,-1)).x;
    float bottom=coarseRead(ivec2(gid)+ivec2(0,1)).x;
    float edge=max(abs(right-left),abs(bottom-top));
    float curvature=max(abs(center.x-0.5*(left+right)),
                        abs(center.x-0.5*(top+bottom)));
    float edgeGate=clamp(1.0-edge/(3.5*sigma+1.e-12),0.0,1.0);
    float curvatureGate=clamp(1.0-curvature/(2.8*sigma+1.e-12),0.0,1.0);
    float authority=clamp(0.38*pc.cfaEvidence1.y*edgeGate*curvatureGate,0.0,0.38);
    float correction=clamp((sum/total-center.x)*authority,-0.65*sigma,0.65*sigma);
    coarseDelta[index]=(isnan(correction)||isinf(correction))?0.0:correction;
}

float coarseCorrectionAt(ivec2 xy) {
    vec2 q=(vec2(xy)-vec2(1.5))/4.0;
    ivec2 base=ivec2(floor(q));
    vec2 alpha=q-vec2(base);
    float a=coarseDelta[coarseIndex(base)];
    float b=coarseDelta[coarseIndex(base+ivec2(1,0))];
    float c=coarseDelta[coarseIndex(base+ivec2(0,1))];
    float d=coarseDelta[coarseIndex(base+ivec2(1,1))];
    float result=mix(mix(a,b,alpha.x),mix(c,d,alpha.x),alpha.y);
    return (isnan(result)||isinf(result))?0.0:result;
}
