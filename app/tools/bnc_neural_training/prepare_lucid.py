"""Import one pinned, SHA-verified CC0 archive for an exploratory corpus.

No extraction of arbitrary archive paths. Group all tiles of a source photograph.
The suffix follows lucid/core/finalize.py::_tag_for_tile's source-stem convention.
PNG RGB without ICC is treated explicitly as rendered sRGB, not sensor truth.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import re
import tarfile
from PIL import Image

REVISION = "c34d3dc53cdc09df21837c1b87863e2fbb254719"
ARCHIVE_SHA = "8591db067f48e3b96dc34c2c85268eff7400b6e12e764597344485f43d2d89ea"
SOURCE = "https://huggingface.co/datasets/Phips/lucid-cc0-v2-hc-512"


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("archive", type=Path)
    p.add_argument("out", type=Path)
    args = p.parse_args()
    with args.archive.open("rb") as f:
        sha = hashlib.file_digest(f, "sha256").hexdigest()
    if sha != ARCHIVE_SHA:
        raise ValueError("archive hash mismatch")
    args.out.mkdir(parents=True, exist_ok=False)
    images = args.out / "images"
    images.mkdir()
    records, excluded, hashes = [], [], set()
    with tarfile.open(args.archive) as archive:
        for member in archive:
            match = re.fullmatch(r"(\d+)_(\d+)\.png", member.name)
            if not member.isfile() or not match or member.size > 8*1024*1024:
                raise ValueError("unrecognized archive layout; require reviewed source grouping")
            data = archive.extractfile(member).read()
            digest = hashlib.sha256(data).hexdigest()
            with Image.open(io.BytesIO(data)) as image:
                if image.mode != "RGB" or image.size != (512, 512) or image.info.get("icc_profile"):
                    excluded.append(dict(path=member.name, reason="mode/shape/ICC requires explicit conversion"))
                    continue
                image.load()
            if digest in hashes:
                excluded.append(dict(path=member.name, reason="exact duplicate"))
                continue
            hashes.add(digest)
            source_id = match[2]
            bucket = int(hashlib.sha256(("bnc-split-v1:"+source_id).encode()).hexdigest()[:8], 16) % 10
            split = "test" if bucket == 0 else "validation" if bucket == 1 else "train"
            (images/member.name).write_bytes(data)
            records.append(dict(path="images/"+member.name, sha256=digest,
                                source=SOURCE+"/tree/"+REVISION,
                                source_image="https://pxhere.com/en/photo/"+source_id,
                                license="CC0-1.0 (dataset publisher; source PxHere)",
                                source_group="pxhere:"+source_id, split=split,
                                encoding="srgb", ground_truth="known_full_rgb"))
    counts = {s: dict(tiles=sum(r["split"] == s for r in records),
                      sourceGroups=len({r["source_group"] for r in records if r["split"] == s}))
              for s in ("train", "validation", "test")}
    manifest = dict(schema="bnc-rgb-corpus-v1", corpus="LUCID CC0 v2 HC512 shard 00000",
                    revision=REVISION, archiveSHA256=sha, images=records, excluded=excluded,
                    splitRule="SHA256(bnc-split-v1:<sourceID>) first32bits modulo10: 0 test,1 validation,2..9 train",
                    limitations=["rendered RGB, approximate inverse sRGB; not calibrated camera radiance",
                                 "single ordered shard; limited coverage, no sufficiency claim",
                                 "source-ID and exact-hash separation; near-duplicate source photos not certified absent",
                                 "512px crops from publisher, not downsampled here"], counts=counts)
    (args.out/"corpus.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps(counts, indent=2))


if __name__ == "__main__":
    main()
