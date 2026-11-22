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
vec3 physicalGuidedFine(ivec2 xy,vec3 postChroma,out vec3 telemetry,bool gate,bool balanced) {
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
    // SF2-D08 mirrors the CPU gain without altering the physical variance.
    float textureRisk=balanced ? max(structure,
        clamp(sceneVariance/(sceneVariance+expected+1.e-12),0.0,1.0)) : 0.0;
    float balancedGain=balanced ? 1.18-0.30*textureRisk : 1.0;
    float authority=clamp(.78*physical*wiener*(gate?1.0-.98*structure:1.0)*balancedGain,0.0,.78);
    float correction=authority*(mean-centre);
    vec3 result=postChroma+vec3(correction);
    if(!physicalChromaFinite(result))return postChroma;
    telemetry=vec3(authority,0.0,structure);
    return result;
}

// Cache the 3x3 guide for the regression and both directional structure tests.
// A zero shape marks invalid RGB and keeps the regression's fail-closed checks.
vec2 physicalGuidedSample(ivec2 pos) {
    vec3 rgb=physicalChromaRead(pos);
    return vec2(opponentAtRgb(rgb).x,physicalChromaFinite(rgb)?physicalChromaShape(pos):0.0);
}

bool physicalGuidedRowValid(vec3 y,vec3 sigma) {
    return !any(isnan(y)) && !any(isinf(y)) &&
        all(greaterThan(sigma,vec3(0.0))) && !any(isinf(sigma));
}

void physicalGuidedMoment(inout vec4 moments,vec2 sampleValue,float centre,
                          float rangeDenominator,float spatialDenominator) {
    float y=sampleValue.x,sigma=sampleValue.y,diff=y-centre;
    float w=exp(-diff*diff/rangeDenominator)/spatialDenominator;
    moments+=vec4(w,w*y,w*y*y,w*pc.padding0*sigma*sigma);
}

float physicalGuidedStructure(vec3 a,vec3 c,vec3 b,vec3 sa,vec3 sc,vec3 sb) {
    vec3 residuals=c-.5*(a+b);
    vec3 noise=pc.padding0*(sc*sc+.25*(sa*sa+sb*sb));
    float residualMean=(residuals.x+residuals.y+residuals.z)/3.0;
    float meanVariance=(noise.x+noise.y+noise.z)/9.0;
    float power=residualMean*residualMean;
    return max(0.0,power-meanVariance)/(power+meanVariance+1.e-12);
}

vec3 physicalGuidedFineBalanced(ivec2 xy,vec3 postChroma,out vec3 telemetry) {
    telemetry=vec3(0);
    if(pc.cfaEvidence1.y<=0.0||!physicalChromaFinite(postChroma))return postChroma;

    vec2 centreSample=physicalGuidedSample(xy);
    float centre=centreSample.x,ss=centreSample.y,v=pc.padding0*ss*ss;
    if(!(v>1.e-12)||isinf(v)||isnan(centre)||isinf(centre))return postChroma;
    float rangeDenominator=8.0*v+1.e-12;
    vec4 moments=vec4(0.0);

    vec2 tl=physicalGuidedSample(xy+ivec2(-1,-1));
    vec2 tc=physicalGuidedSample(xy+ivec2(0,-1));
    vec2 tr=physicalGuidedSample(xy+ivec2(1,-1));
    vec3 topY=vec3(tl.x,tc.x,tr.x),topSigma=vec3(tl.y,tc.y,tr.y);
    if(!physicalGuidedRowValid(topY,topSigma))return postChroma;
    physicalGuidedMoment(moments,tl,centre,rangeDenominator,1.8);
    physicalGuidedMoment(moments,tc,centre,rangeDenominator,1.4);
    physicalGuidedMoment(moments,tr,centre,rangeDenominator,1.8);

    vec2 ml=physicalGuidedSample(xy+ivec2(-1,0));
    vec2 mr=physicalGuidedSample(xy+ivec2(1,0));
    vec3 midY=vec3(ml.x,centre,mr.x),midSigma=vec3(ml.y,ss,mr.y);
    if(!physicalGuidedRowValid(midY,midSigma))return postChroma;
    physicalGuidedMoment(moments,ml,centre,rangeDenominator,1.4);
    // The centre's bilateral and spatial weights are exactly 1.
    moments+=vec4(1.0,centre,centre*centre,v);
    physicalGuidedMoment(moments,mr,centre,rangeDenominator,1.4);

    vec2 bl=physicalGuidedSample(xy+ivec2(-1,1));
    vec2 bc=physicalGuidedSample(xy+ivec2(0,1));
    vec2 br=physicalGuidedSample(xy+ivec2(1,1));
    vec3 botY=vec3(bl.x,bc.x,br.x),botSigma=vec3(bl.y,bc.y,br.y);
    if(!physicalGuidedRowValid(botY,botSigma))return postChroma;
    physicalGuidedMoment(moments,bl,centre,rangeDenominator,1.8);
    physicalGuidedMoment(moments,bc,centre,rangeDenominator,1.4);
    physicalGuidedMoment(moments,br,centre,rangeDenominator,1.8);

    float total=moments.x;
    if(!(total>0.0)||isinf(total))return postChroma;
    float mean=moments.y/total,expected=moments.w/total;
    float sceneVariance=max(0.0,moments.z/total-mean*mean-.8*expected);
    float horizontal=physicalGuidedStructure(
        vec3(tl.x,ml.x,bl.x),vec3(tc.x,centre,bc.x),vec3(tr.x,mr.x,br.x),
        vec3(tl.y,ml.y,bl.y),vec3(tc.y,ss,bc.y),vec3(tr.y,mr.y,br.y));
    float vertical=physicalGuidedStructure(topY,midY,botY,topSigma,midSigma,botSigma);
    float structure=max(horizontal,vertical);
    float physical=pc.cfaEvidence1.y*v/(v+.001*centre*centre);
    float wiener=v/(v+2.0*sceneVariance+1.e-12);
    float textureRisk=max(structure,clamp(sceneVariance/(sceneVariance+expected+1.e-12),0.0,1.0));
    float balancedGain=1.18-0.30*textureRisk;
    float authority=clamp(.78*physical*wiener*(1.0-.98*structure)*balancedGain,0.0,.78);
    vec3 result=postChroma+vec3(authority*(mean-centre));
    if(!physicalChromaFinite(result))return postChroma;
    telemetry=vec3(authority,0.0,structure);
    return result;
}

// Mode-3 bits 2..4 carry stages: 0 OFF, 1 legacy, 2 Guided Fine,
// 3 Guided Fine Balanced, 4 Guided Fine+Coarse, 5 Guided Fine+Coarse with confidence.
// Coarse buffers are read only for stages 4 and 5.
vec3 physicalLumaDenoise(ivec2 xy,vec3 postChroma,out vec3 telemetry) {
    uint stage=(pc.residualStride>>2u)&7u;
    telemetry=vec3(0);
    if(stage==1u)return physicalLegacyLumaDenoise(xy,postChroma,telemetry);
    if(stage==2u)return physicalGuidedFine(xy,postChroma,telemetry,true,false);
    if(stage==3u)return physicalGuidedFineBalanced(xy,postChroma,telemetry);
#if defined(BNCAM_ENABLE_GPU_BENCHMARKS)
    if(stage==6u)return physicalGuidedFine(xy,postChroma,telemetry,true,true);
#endif
    if(stage==4u) {
        vec3 fineTelemetry;
        vec3 fine=physicalGuidedFine(xy,postChroma,fineTelemetry,true,false);
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
        vec3 fine=physicalGuidedFine(xy,postChroma,fineTelemetry,true,false);
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
