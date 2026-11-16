// Phase-4 bounded Guided Fine remains debug-only; stage OFF by default.
// CPU oracle: PhysicalLumaDenoise.h. Mode 3 only; evidence reads immutable
// demosaic Y. Add the existing camera-RGB luma delta AFTER physical chroma.
vec3 physicalLegacyLumaDenoise(ivec2 xy,vec3 postChroma,out vec3 telemetry) {
    telemetry=vec3(0);
    if(pc.cfaEvidence1.y<=0.0 || !physicalChromaFinite(postChroma)) return postChroma;
    float center=opponentAtRgb(physicalChromaRead(xy)).x;
    float s=physicalChromaShape(xy),variance=pc.padding0;
    float v=variance*s*s;
    if(!(v>0.0) || isnan(v) || isinf(v)) return postChroma;
    float physical=pc.cfaEvidence1.y*v/(v+0.001*center*center);
    float pressure=physicalNoisePressure(v,center);
    float correction=0.0;
    const ivec2 directions[4]=ivec2[4](ivec2(1,0),ivec2(0,1),ivec2(1,1),ivec2(1,-1));
    for(int band=0;band<2;++band) {
        int radius=band+1;
        float residuals[4],likelihood[4];float structure=0.0;
        for(int dir=0;dir<4;++dir) {
            ivec2 d=directions[dir],t=ivec2(-d.y,d.x);
            float sum=0.0,power=0.0,noise=0.0;
            for(int k=-2;k<=2;++k) {
                ivec2 p=xy+k*t,a=p-radius*d,b=p+radius*d;
                float cY=opponentAtRgb(physicalChromaRead(p)).x;
                float aY=opponentAtRgb(physicalChromaRead(a)).x;
                float bY=opponentAtRgb(physicalChromaRead(b)).x;
                float cs=physicalChromaShape(p),as=physicalChromaShape(a),bs=physicalChromaShape(b);
                float n=variance*(cs*cs+0.25*(as*as+bs*bs));
                float r=cY-0.5*(aY+bY);
                if(isnan(r)||isinf(r)||isnan(n)||isinf(n)||!(n>0.0)) return postChroma;
                if(k==0)residuals[dir]=r;
                sum+=r;power+=r*r;noise+=n;
            }
            power*=0.2;noise*=0.2;
            float mean=sum*0.2,coherentPower=max(0.0,mean*mean-noise*0.2);
            float signal=max(0.0,power-noise);
            structure=max(structure,coherentPower/(coherentPower+noise*0.2));
            likelihood[dir]=noise/(noise+4.0*signal);
        }
        telemetry.z=max(telemetry.z,structure);
        float available=band==0 ? 0.28+0.32*pressure : 0.16+0.20*pressure;
        float a=physical*(1.0-structure)*available;
        for(int dir=0;dir<4;++dir) {
            correction+=0.25*a*likelihood[dir]*residuals[dir];
            telemetry[band]+=0.25*a*likelihood[dir];
        }
    }
    vec3 result=postChroma-vec3(correction);
    return physicalChromaFinite(result)?result:postChroma;
}

// SF2-D04: CPU oracle SingleFrameGuidedLuma.h, stage 2; immutable
// demosaic evidence, bounded additive Y, original post-chroma opponents.
vec3 physicalGuidedFine(ivec2 xy,vec3 postChroma,out vec3 telemetry,bool gate) {
    telemetry=vec3(0);
    if(pc.cfaEvidence1.y<=0.0||!physicalChromaFinite(postChroma))return postChroma;
    float centre=opponentAtRgb(physicalChromaRead(xy)).x;
    float ss=physicalChromaShape(xy),v=pc.padding0*ss*ss;
    if(!(v>1.e-12)||isnan(v)||isinf(v)||isnan(centre)||isinf(centre))return postChroma;
    float physical=pc.cfaEvidence1.y*v/(v+.001*centre*centre);
    float total=0.0,sum=0.0,sum2=0.0,noise=0.0;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx) {
        ivec2 pos=xy+ivec2(dx,dy);
        vec3 p=physicalChromaRead(pos);
        float sigma=physicalChromaShape(pos);
        if(!physicalChromaFinite(p)||!(sigma>0.0)||isnan(sigma)||isinf(sigma))return postChroma;
        float y=opponentAtRgb(p).x,diff=y-centre;
        float w=exp(-diff*diff/(8.0*v+1.e-12))/(1.0+.4*float(dx*dx+dy*dy));
        total+=w;sum+=w*y;sum2+=w*y*y;noise+=w*pc.padding0*sigma*sigma;
    }
    if(!(total>0.0)||isnan(total)||isinf(total))return postChroma;
    float mean=sum/total,expected=noise/total;
    float sceneVariance=max(0.0,sum2/total-mean*mean-.8*expected);
    float wiener=v/(v+2.0*sceneVariance+1.e-12);
    float structure=0.0;
    if(gate)for(int axis=0;axis<2;++axis) {
        float residualMean=0.0,meanNoise=0.0;
        for(int k=-1;k<=1;++k) {
            ivec2 p=xy+(axis==0?ivec2(0,k):ivec2(k,0));
            ivec2 d=axis==0?ivec2(1,0):ivec2(0,1);
            float a=opponentAtRgb(physicalChromaRead(p-d)).x;
            float b=opponentAtRgb(physicalChromaRead(p+d)).x;
            float c=opponentAtRgb(physicalChromaRead(p)).x;
            float sa=physicalChromaShape(p-d),sb=physicalChromaShape(p+d),sc=physicalChromaShape(p);
            if(any(isnan(vec3(a,b,c)))||any(isinf(vec3(a,b,c)))||
               !(sa>0.0&&sb>0.0&&sc>0.0))return postChroma;
            residualMean+=c-.5*(a+b);
            meanNoise+=pc.padding0*(sc*sc+.25*(sa*sa+sb*sb));
        }
        residualMean/=3.0;
        float meanVariance=meanNoise/9.0;
        float evidence=max(0.0,residualMean*residualMean-meanVariance)/
            (residualMean*residualMean+meanVariance+1.e-12);
        structure=max(structure,evidence);
    }
    float authority=clamp(.78*physical*wiener*(gate?1.0-.98*structure:1.0),0.0,.78);
    float correction=authority*(mean-centre);
    vec3 result=postChroma+vec3(correction);
    if(!physicalChromaFinite(result))return postChroma;
    telemetry=vec3(authority,0.0,structure);
    return result;
}
// Mode-3 bits 2..4 carry stages: 0 OFF, 1 legacy, 2 Guided Fine,
// 4 Guided Fine+Coarse, 5 Guided Fine+Coarse with confidence.\n// Coarse buffers are read only for stages 4 and 5.
// The fine kernel remains source-identical to phase 4.
vec3 physicalLumaDenoise(ivec2 xy,vec3 postChroma,out vec3 telemetry) {
    uint stage=(pc.residualStride>>2u)&7u;
    telemetry=vec3(0);
    if(stage==1u)return physicalLegacyLumaDenoise(xy,postChroma,telemetry);
    if(stage==2u)return physicalGuidedFine(xy,postChroma,telemetry,true);
    if(stage==4u) {
        vec3 fineTelemetry;
        vec3 fine=physicalGuidedFine(xy,postChroma,fineTelemetry,true);
        float coarse=coarseCorrectionAt(xy);
        vec3 combined=fine+vec3(coarse);
        if(!physicalChromaFinite(combined))return fine;
        telemetry=vec3(fineTelemetry.x,abs(coarse),fineTelemetry.z);
        return combined;
    }
    // SF2-D06: stage 5 computes the SAME fine+coarse candidate as stage 4,
    // then applies one bounded confidence factor. No additional denoising,
    // chroma modification, sharpening or second variance scale.
    if(stage==5u) {
        vec3 fineTelemetry;
        vec3 fine=physicalGuidedFine(xy,postChroma,fineTelemetry,true);
        float fineDelta=fine.r-postChroma.r;
        float coarseDelta=coarseCorrectionAt(xy);
        Sf2ConfidenceEvidence evidence=sf2ResidualConfidenceAt(xy,fineDelta,coarseDelta);
        if(evidence.valid<0.5)return postChroma;
        vec3 gated=postChroma+vec3(evidence.weight*(fineDelta+coarseDelta));
        if(!physicalChromaFinite(gated))return postChroma;
        telemetry=vec3(fineTelemetry.x*evidence.weight,
                       abs(coarseDelta)*evidence.weight,
                       max(evidence.coherence,evidence.edge));
        return gated;
    }
    return postChroma;
}
