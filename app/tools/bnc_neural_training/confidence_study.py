"""Offline regional opponent-consistency study. No production blending or blur.

The score is disagreement with cross-route median opponent GRADIENTS, not color
amplitude, and is not a calibrated probability. Oracle error is used only to audit
the score after selection, never to choose a route. Joint classical failure remains.
"""
from pathlib import Path
import argparse
import json
import numpy as np
import torch
from .contracts import sample
from .inspect_candidate import reconstruct
from .package import load_reference


def opponent(v):
    return np.stack((v[...,0]-v[...,1], v[...,2]-v[...,1]), -1)


def region_scores(candidates):
    """Returns uncalibrated disagreement; low opponent magnitude is not a feature."""
    gradients = []
    for rgb in candidates:
        o = opponent(rgb)
        gradients.append(np.concatenate((np.diff(o,axis=0).reshape(-1,2), np.diff(o,axis=1).reshape(-1,2)),0))
    gradients = np.stack(gradients)
    consensus = np.median(gradients, axis=0)
    return np.mean(abs(gradients-consensus), axis=(1,2))


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--root", type=Path, default=Path("build/bnc-neural"))
    args = p.parse_args()
    root=args.root
    path=root/"training/opponent-v2-full/candidate.bncmodel"
    sha=path.with_suffix(".bncmodel.sha256").read_text().strip()
    model,_=load_reference(path,sha)
    torch.set_num_threads(2)
    labels=("malvar","amaze","bnc_neural_reference")
    rows=[]
    for fixture in sorted((root/"synthetic/fixtures").glob("synthetic-*")):
        truth=np.fromfile(fixture/"ground-truth.rgb.f32","f4").reshape(256,256,3)
        candidates=[np.fromfile(root/"synthetic/demosaic"/fixture.name/(label+"-lsc-off.rgb.f32"),"f4").reshape(truth.shape)
                    for label in labels[:2]]
        candidates.append(reconstruct(model,sample(truth,"BGGR")))
        patches=[]
        for y in range(8,217,32):
            for x in range(8,217,32):
                crops=[v[y:y+32,x:x+32] for v in candidates]
                scores=region_scores(crops)
                winner=int(np.argmin(scores))
                target=opponent(truth[y:y+32,x:x+32])
                errors=[float(np.mean((opponent(v)-target)**2)) for v in crops]
                patches.append(dict(x=x,y=y,selected=labels[winner],disagreement=scores.tolist(),
                    opponentErrorMse=errors,selectionRegretMse=errors[winner]-min(errors),
                    selectedWorseThanBothClassical=errors[winner]>max(errors[:2])+1e-12))
        rows.append(dict(fixture=fixture.name,regions=patches,
                         selectedRms=float(np.sqrt(np.mean([q['opponentErrorMse'][labels.index(q['selected'])] for q in patches]))),
                         oracleRms=float(np.sqrt(np.mean([min(q['opponentErrorMse']) for q in patches]))),
                         worseThanBothClassicalRegions=sum(q['selectedWorseThanBothClassical'] for q in patches)))
    (root/"metrics/regional-opponent-confidence-study.json").write_text(json.dumps(dict(
        mode="offline diagnostic, not Auto Hybrid; no blended image is published",packageSHA256=sha,
        score="L1 cross-route median RG/BG gradient disagreement",rows=rows,
        interpretation="Agreement is not truth; this score is uncalibrated and cannot authorize a third route."),indent=2))
    print(json.dumps([{k:v for k,v in r.items() if k!='regions'} for r in rows],indent=2))


if __name__=="__main__":
    main()
