// SF2-D06: matched CPU oracle SingleFrameResidualConfidence.h.
// Per-pixel confidence only; never estimate/replace sensor S/O or create a
// second sharpening, colour, spatial-noise or tone-mapping subsystem.
// This file is included AFTER existing physical-chroma and coarse utilities.

float sf2CoherentEvidence(float mean,float varianceOfMean) {
    if(isnan(mean)||isinf(mean)||!(varianceOfMean>0.0)||
       isnan(varianceOfMean)||isinf(varianceOfMean))return 0.0;
    float sq=mean*mean;
    if(isnan(sq)||isinf(sq))return 0.0;
    return clamp(max(0.0,sq-varianceOfMean)/(sq+varianceOfMean+1.e-12),0.0,1.0);
}

struct Sf2ConfidenceEvidence {
    float weight;
    float coherence;
    float edge;
    float valid;
};

// Four 3-tap oriented stencils on immutable pre-chroma demosaic RGB. No
// optical-flow, ML inference, extra SSBO or GPU->CPU transfer.
Sf2ConfidenceEvidence sf2ResidualConfidenceAt(
        ivec2 xy,float fineDelta,float coarseDelta) {
    Sf2ConfidenceEvidence outValue=Sf2ConfidenceEvidence(0.0,0.0,0.0,0.0);
    float confidence=pc.cfaEvidence1.y;
    if(!(confidence>0.0)||confidence>1.0||isnan(confidence)||isinf(confidence)||
       isnan(fineDelta)||isinf(fineDelta)||isnan(coarseDelta)||isinf(coarseDelta))return outValue;
    vec3 rgb=physicalChromaRead(xy);
    float local=physicalChromaShape(xy);
    float variance=pc.padding0*local*local;
    if(!physicalChromaFinite(rgb)||!(local>0.0)||
       isnan(local)||isinf(local)||!(variance>1.e-12)||
       isnan(variance)||isinf(variance))return outValue;
    const ivec2 d[4]=ivec2[4](ivec2(1,0),ivec2(0,1),
                              ivec2(1,1),ivec2(1,-1));
    float structure=0.0,edge=0.0;
    for(int dir=0;dir<4;++dir) {
        float lineSum=0.0,edgeSum=0.0,lineNoise=0.0,edgeNoise=0.0;
        ivec2 side=ivec2(-d[dir].y,d[dir].x);
        for(int k=-1;k<=1;++k) {
            ivec2 p=xy+k*side;
            ivec2 a=p-d[dir],b=p+d[dir];
            vec3 rc=physicalChromaRead(p);
            vec3 ra=physicalChromaRead(a);
            vec3 rb=physicalChromaRead(b);
            float sc=physicalChromaShape(p);
            float sa=physicalChromaShape(a);
            float sb=physicalChromaShape(b);
            if(!physicalChromaFinite(rc)||!physicalChromaFinite(ra)||
               !physicalChromaFinite(rb)||!(sc>0.0&&sa>0.0&&sb>0.0)||
               isnan(sc)||isinf(sc)||isnan(sa)||isinf(sa)||isnan(sb)||isinf(sb))
                return outValue;
            float c=opponentAtRgb(rc).x;
            float av=opponentAtRgb(ra).x;
            float bv=opponentAtRgb(rb).x;
            float vc=pc.padding0*sc*sc;
            float va=pc.padding0*sa*sa;
            float vb=pc.padding0*sb*sb;
            if(!(vc>0.0&&va>0.0&&vb>0.0)||
               isnan(vc)||isinf(vc)||isnan(va)||isinf(va)||isnan(vb)||isinf(vb))
                return outValue;
            lineSum+=c-.5*(av+bv);
            edgeSum+=.5*(bv-av);
            lineNoise+=vc+.25*(va+vb);
            edgeNoise+=.25*(va+vb);
        }
        float inv=1.0/3.0;
        structure=max(structure,sf2CoherentEvidence(lineSum*inv,lineNoise*inv*inv));
        edge=max(edge,sf2CoherentEvidence(edgeSum*inv,edgeNoise*inv*inv));
    }
    // One kernel-only proxy bit. It is NOT a measurement of total post-demosaic
    // correlation; unknown adaptive routes never gain fictitious rho=0.
    bool kernelProxyAvailable=(pc.residualStride & 32u)!=0u;
    float demosaicSupport=kernelProxyAvailable?0.92:0.78;
    float physicalSupport=clamp(2.0*confidence,0.0,1.0);
    float delta=fineDelta+coarseDelta;
    float normalized=abs(delta)/(1.6*sqrt(variance)+1.e-12);
    float beyond=max(0.0,normalized-1.0);
    float residualSupport=1.0/(1.0+.7*beyond*beyond);
    float opposition=max(0.0,-fineDelta*coarseDelta);
    float support=abs(fineDelta*coarseDelta)+.10*variance+1.e-12;
    float levelAgreement=1.0-.50*opposition/support;
    float structureSupport=clamp(1.0-.94*max(structure,edge),.06,1.0);
    float weight=clamp(physicalSupport*demosaicSupport*structureSupport*
                       residualSupport*levelAgreement,0.0,1.0);
    if(isnan(weight)||isinf(weight))return outValue;
    return Sf2ConfidenceEvidence(weight,structure,edge,1.0);
}
