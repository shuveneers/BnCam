"""Remove perceptual duplicates after the importer has unioned source groups."""
from pathlib import Path
import hashlib
import json


def finalize(path):
    path=Path(path);data=json.loads(path.read_text())
    if data.get("dedupFinalized"):
        expected=path.with_suffix(".json.sha256").read_text().strip()
        if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise ValueError("finalized corpus SHA mismatch")
        return
    # Every pair points from a later tile to an earlier tile, so the oldest survives.
    removed={p["a"] for p in data["nearDuplicatePairs"]}
    old=data["images"];data["images"]=[r for r in old if r["sha256"] not in removed]
    data["perceptualDuplicatesRemoved"]=len(old)-len(data["images"])
    data["dedupFinalized"]=True
    data["counts"]={s:dict(tiles=sum(r["split"]==s for r in data["images"]),
        sourceGroups=len({r["source_group"] for r in data["images"] if r["split"]==s}),
        sourcePhotos=len({r["source_id"] for r in data["images"] if r["split"]==s})) for s in ("train","validation","test")}
    if data["counts"]["train"]["sourceGroups"]<1000:raise ValueError("insufficient deduplicated sources; STOP")
    temp=path.with_suffix(".json.partial");temp.write_text(json.dumps(data,indent=2));temp.replace(path)
    with path.open("rb") as f:sha=hashlib.file_digest(f,"sha256").hexdigest()
    path.with_suffix(".json.sha256").write_text(sha+"\n")
    print(json.dumps(dict(counts=data["counts"],nearDuplicatesRemoved=data["perceptualDuplicatesRemoved"]),indent=2))


if __name__=="__main__":
    import argparse
    p=argparse.ArgumentParser(__doc__);p.add_argument("manifest",type=Path);finalize(p.parse_args().manifest)
