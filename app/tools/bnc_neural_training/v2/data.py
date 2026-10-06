"""Deterministic 60/40 source sampling, disjoint validation and same-signal targets."""
from collections import OrderedDict
from pathlib import Path
import hashlib
import json
import numpy as np
import torch
from torch.utils.data import Dataset
from ..contracts import canonicalize, pack, sample
from ..corpus import read_linear, augment_camera
from .procedural import scene, rng_for


def read_corpus(path):
    path=Path(path)
    with path.open("rb") as f:sha=hashlib.file_digest(f,"sha256").hexdigest()
    if path.with_suffix(".json.sha256").read_text().strip()!=sha:
        raise ValueError("corpus manifest SHA mismatch")
    data=json.loads(path.read_text())
    if not data.get("complete") or len(data["archives"])!=51 or data["counts"]["train"]["sourceGroups"]<1000:
        raise ValueError("STOP_BEFORE_TRAINING: full qualified corpus with >=1000 training source groups required")
    groups={};hashes=set();parts={s:[] for s in ("train","validation","test")}
    for r in data["images"]:
        if groups.setdefault(r["source_group"],r["split"])!=r["split"] or r["sha256"] in hashes:
            raise ValueError("split leakage or duplicate content")
        hashes.add(r["sha256"])
        parts[r["split"]].append(dict(path=str(path.parent/r["path"]),sha256=r["sha256"],group=r["source_group"]))
    return parts,dict(manifest=str(path),sha256=sha,counts=data["counts"],revision=data["revision"])


def encode(rgb,pattern):
    raw=sample(rgb,pattern);packed,g=pack(raw,pattern)
    truth=canonicalize(rgb,g)/4
    return (torch.from_numpy(packed.transpose(2,0,1).copy()),torch.from_numpy(truth.transpose(2,0,1).copy()))


class TrainingData(Dataset):
    def __init__(self,records,length,seed=823109,start_index=0):
        self.records,self.length,self.seed,self.start_index=records,length,seed,start_index
        self.cache=OrderedDict()

    def __len__(self):return self.length

    def natural(self,record):
        path=record["path"]
        if path not in self.cache:
            if len(self.cache)>=16:self.cache.popitem(last=False)
            # Verify each decoded source, including after eviction; importer checks archives too.
            with open(path,"rb") as f:
                if hashlib.file_digest(f,"sha256").hexdigest()!=record["sha256"]:
                    raise ValueError("training source changed after import")
            self.cache[path]=read_linear(dict(resolved_path=path,encoding="srgb"))
        self.cache.move_to_end(path)
        return self.cache[path]

    def __getitem__(self,index):
        index+=self.start_index;rng=rng_for("train",index,self.seed)
        # Exactly 60/40 over each five successive samples, not a noisy coin toss.
        if index%5<3:
            record=self.records[int(rng.integers(len(self.records)))];image=self.natural(record)
            y,x=rng.integers(0,image.shape[0]-255),rng.integers(0,image.shape[1]-255)
            rgb=np.rot90(image[y:y+256,x:x+256],int(rng.integers(4)))
            if rng.random()<.5:rgb=rgb[:,::-1]
            for _ in range(128):
                transformed,matrix=augment_camera(rgb,rng)
                if np.linalg.cond(matrix)<=5:
                    rgb=transformed;break
            else:raise ValueError("camera matrix conditioning failure")
        else:
            rgb,_=scene(index,"train",256,self.seed)
        pattern=("RGGB","GRBG","GBRG","BGGR")[int(rng.integers(4))]
        return encode(rgb,pattern)


def validation_cases(records,split="validation",seed=823109,natural_count=64,procedural_count=52):
    # Fixed selection: one tile per independent source group, no score-based crop mining.
    unique={}
    for record in records:unique.setdefault(record["group"],record)
    selected=sorted(unique.values(),key=lambda r:hashlib.sha256((f"bnc-v2-{split}:"+r["group"]).encode()).hexdigest())[:natural_count]
    cases=[]
    for record in selected:
        with open(record["path"],"rb") as f:
            if hashlib.file_digest(f,"sha256").hexdigest()!=record["sha256"]:raise ValueError("validation source SHA mismatch")
        image=read_linear(dict(resolved_path=record["path"],encoding="srgb"))
        y,x=(image.shape[0]-256)//2,(image.shape[1]-256)//2
        a,b=encode(image[y:y+256,x:x+256].copy(),"BGGR")
        cases.append((record["group"],a,b))
    for index in range(procedural_count):
        rgb,meta=scene(index,split,256,seed);a,b=encode(rgb,"BGGR")
        cases.append((f"analytic:{split}:{index}:{meta['kind']}",a,b))
    return cases
