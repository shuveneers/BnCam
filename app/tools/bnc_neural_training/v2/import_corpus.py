"""Import all pinned LUCID shards, with source/near-duplicate group disjointness.

Downloaded bytes are never executed. All tar member names and content hashes
are checked; only decoded RGB PNGs enter the governed corpus. Existing Phase-3
corpus/assets are not modified. Downloads can resume at complete verified shards.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import argparse
import hashlib
import io
import json
import re
import shutil
import tarfile
import time
import urllib.request
import numpy as np
from PIL import Image

REPO="Phips/lucid-cc0-v2-hc-512"
PIN="c34d3dc53cdc09df21837c1b87863e2fbb254719"


def sha_file(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f,"sha256").hexdigest()


class Groups:
    def __init__(self):
        self.parent={}

    def find(self, value):
        self.parent.setdefault(value,value)
        root=value
        while self.parent[root]!=root:
            root=self.parent[root]
        while value!=root:
            nxt=self.parent[value];self.parent[value]=root;value=nxt
        return root

    def union(self, a, b):
        a,b=self.find(a),self.find(b)
        if a!=b:
            self.parent[max(a,b)]=min(a,b)


def download(record, root):
    path=root/"archives"/record["path"]
    expected=record["lfs"]["oid"]
    if path.exists() and path.stat().st_size==record["size"] and sha_file(path)==expected:
        return path
    if record["path"]=="shard-00000.tar":
        old=Path("build/bnc-neural/training/lucid-shard-00000.tar")
        if old.exists() and sha_file(old)==expected:
            shutil.copyfile(old,path)
            return path
    url=f"https://huggingface.co/datasets/{REPO}/resolve/{PIN}/{record['path']}?download=true"
    partial=path.with_suffix(".partial")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url,timeout=90) as response, partial.open("wb") as f:
                shutil.copyfileobj(response,f,1024*1024)
            if partial.stat().st_size!=record["size"] or sha_file(partial)!=expected:
                raise ValueError("archive SHA/length mismatch: "+record["path"])
            partial.replace(path)
            return path
        except Exception:
            if attempt==3:
                raise
            time.sleep(2**attempt)


def main():
    p=argparse.ArgumentParser(__doc__)
    p.add_argument("--out",type=Path,default=Path("build/bnc-neural-v2/corpus"))
    p.add_argument("--remote",type=Path,default=Path("build/bnc-neural-v2/provenance/dataset-remote.json"))
    args=p.parse_args();root=args.out
    for d in ("archives","images"):
        (root/d).mkdir(parents=True,exist_ok=True)
    remote=json.loads(args.remote.read_text(encoding="utf-8-sig"))
    if remote["revision"]!=PIN:
        raise ValueError("dataset revision changed; review provenance before import")
    records=[r for r in remote["files"] if re.fullmatch(r"shard-\d{5}\.tar",r["path"])]
    if len(records)!=51:
        raise ValueError("expected the full 51-shard pinned corpus")
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs={pool.submit(download,r,root):r for r in records}
        for completed in as_completed(jobs):
            path=completed.result()
            print(json.dumps(dict(downloaded=path.name,bytes=path.stat().st_size)),flush=True)
    groups=Groups();images=[];hashes={};excluded=[];near_pairs=[];buckets={};thumbs=[];dhashes=[]
    masks=(13,13,13,13,12)
    def bands(value):
        shift=0
        for band,bits in enumerate(masks):
            yield (band,(value>>shift)&((1<<bits)-1));shift+=bits
    for record in records:
        with tarfile.open(root/"archives"/record["path"]) as archive:
            for member in archive:
                match=re.fullmatch(r"(\d+)_(\d+)\.png",member.name)
                if not member.isfile() or not match or member.size>8*1024*1024:
                    raise ValueError("unexpected tar layout: "+member.name)
                data=archive.extractfile(member).read();sha=hashlib.sha256(data).hexdigest();source="pxhere:"+match[2]
                groups.find(source)
                if sha in hashes:
                    groups.union(source,images[hashes[sha]]["source_id"])
                    excluded.append(dict(name=member.name,source=source,reason="exact hash duplicate",sha256=sha))
                    continue
                with Image.open(io.BytesIO(data)) as image:
                    if image.mode!="RGB" or image.size!=(512,512) or image.info.get("icc_profile"):
                        excluded.append(dict(name=member.name,reason="mode/size/ICC requires review"));continue
                    image.load()
                    gray=np.asarray(image.convert("L").resize((9,8),Image.Resampling.BILINEAR))
                    bits=(gray[:,1:]>gray[:,:-1]).ravel()
                    dhash=sum(int(v)<<i for i,v in enumerate(bits))
                    thumb=np.asarray(image.resize((16,16),Image.Resampling.BILINEAR)).astype(np.int16)
                neighbors=set()
                for key in bands(dhash):
                    neighbors.update(buckets.get(key,()))
                for idx in neighbors:
                    if groups.find(source)==groups.find(images[idx]["source_id"]):
                        continue
                    if (dhash^dhashes[idx]).bit_count()<=4 and np.mean(abs(thumb-thumbs[idx]))<=5.1:
                        groups.union(source,images[idx]["source_id"])
                        near_pairs.append(dict(a=sha,b=images[idx]["sha256"],sourceA=source,sourceB=images[idx]["source_id"]))
                idx=len(images);hashes[sha]=idx
                for key in bands(dhash):
                    buckets.setdefault(key,[]).append(idx)
                thumbs.append(thumb);dhashes.append(dhash)
                target=root/"images"/sha[:2]/(sha+".png");target.parent.mkdir(exist_ok=True)
                if not target.exists():target.write_bytes(data)
                images.append(dict(path=str(target.relative_to(root)).replace("\\","/"),sha256=sha,
                    source=f"https://huggingface.co/datasets/{REPO}/tree/{PIN}",source_id=source,
                    source_image="https://pxhere.com/en/photo/"+match[2],archive=record["path"],member=member.name,
                    license="CC0-1.0 (publisher; PxHere source)",encoding="srgb",ground_truth="known_full_rgb",
                    perceptual_dhash=f"{dhash:016x}"))
        print(json.dumps(dict(indexed=record["path"],images=len(images),nearPairs=len(near_pairs))),flush=True)
    for image in images:
        cluster=groups.find(image["source_id"]);image["source_group"]=cluster
        bucket=int(hashlib.sha256(("bnc-v2-split:"+cluster).encode()).hexdigest()[:8],16)%10
        image["split"]="test" if bucket==0 else "validation" if bucket==1 else "train"
    counts={s:dict(tiles=sum(r["split"]==s for r in images),sourceGroups=len({r["source_group"] for r in images if r["split"]==s}),
                  sourcePhotos=len({r["source_id"] for r in images if r["split"]==s})) for s in ("train","validation","test")}
    if counts["train"]["sourceGroups"]<1000:
        raise ValueError("STOP_BEFORE_TRAINING: fewer than 1000 independent deduplicated training sources")
    manifest=dict(schema="bnc-rgb-corpus-v1",revision=PIN,archives=records,images=images,counts=counts,
        deduplication="exact SHA; dHash Hamming<=4 AND 16x16 RGB MAE<=5.1/255; union source identities before splitting",
        nearDuplicatePairs=near_pairs,excluded=excluded,complete=True,
        limitations=["rendered RGB; inverse sRGB is not calibrated camera radiance", "perceptual dedup is conservative, not proof of absence of every related scene"])
    partial=root/"corpus.json.partial";partial.write_text(json.dumps(manifest,indent=2),encoding="utf-8")
    partial.replace(root/"corpus.json")
    (root/"corpus.json.sha256").write_text(sha_file(root/"corpus.json")+"\n")
    print(json.dumps(dict(status="CORPUS_READY",counts=counts)),flush=True)


if __name__=="__main__":main()
