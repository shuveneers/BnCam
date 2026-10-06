"""Held-out natural RGB and real-RAW crop diagnostics for a research candidate.

Real RAW metrics are proxies only. This does not run FLLF or claim GPU deployment.
"""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
import torch
from PIL import Image, ImageDraw
from app.tools.raw_truth.quality_metrics import measure
from .contracts import pack, merge, sample, measured_mask
from .corpus import load_manifest, read_linear
from .evaluate import metrics
from .model import infer_tiled
from .package import load_reference


def reconstruct(model, raw, cfa="BGGR", offset=(0, 0)):
    from .v2.model import BncNeuralV2, MissingRgbAdapter
    if isinstance(model, BncNeuralV2):
        model = MissingRgbAdapter(model)
    packed, g = pack(raw, cfa, crop_offset=offset)
    inp = torch.from_numpy(packed.transpose(2, 0, 1).copy()).unsqueeze(0)
    prediction = infer_tiled(model, inp).squeeze(0).permute(1, 2, 0).numpy()
    rgb = merge(raw, prediction, g)
    assert np.array_equal(rgb[measured_mask(g)].view("u4"), raw.ravel().view("u4"))
    return rgb


def display(v):
    v = np.clip(v, 0, 1)
    return Image.fromarray(np.rint(255*np.where(v<=.0031308, 12.92*v, 1.055*v**(1/2.4)-.055)).astype("u1"))


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--package", type=Path, required=True)
    p.add_argument("--sha", required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--phase2", type=Path, default=Path("build/phase2-raw-quality"))
    args = p.parse_args()
    torch.set_num_threads(2)
    model, identity = load_reference(args.package, args.sha)
    if identity["purpose"] != "research_candidate":
        raise ValueError("requires genuinely trained research candidate")
    args.out.mkdir(parents=True, exist_ok=True)
    test = [r for r in load_manifest(args.manifest) if r["split"] == "test"]
    natural = []
    for record in test:
        rgb = read_linear(record)
        y, x = (rgb.shape[0]-128)//2, (rgb.shape[1]-128)//2
        rgb = rgb[y:y+128, x:x+128].copy()
        actual = reconstruct(model, sample(rgb, "BGGR"))
        q = metrics(actual[8:-8, 8:-8], rgb[8:-8, 8:-8])
        q.update(path=record["path"], sourceGroup=record["source_group"],
                 rgb_rmse=float(np.sqrt(np.mean((actual[8:-8, 8:-8]-rgb[8:-8, 8:-8])**2))))
        natural.append(q)
    (args.out/"natural-heldout.json").write_text(json.dumps(dict(
        packageSHA256=args.sha, crop="fixed center 128x128; 8px metric border", results=natural), indent=2))
    rows = []
    ids = json.loads((args.phase2/"fixture-ids.json").read_text())
    for id in ids:
        fixture = args.phase2/"fixtures"/id
        meta = json.loads((fixture/"metadata.json").read_text())
        h, w = meta["height"], meta["width"]
        rawfile = fixture/"normalized-on.f32"
        raw = np.memmap(rawfile, mode="r", dtype="f4", shape=(h, w))
        classical = {label: np.memmap(args.phase2/"demosaic"/id/(label+"-lsc-on.rgb.f32"),
                                     mode="r", dtype="f4", shape=(h, w, 3)) for label in ("malvar", "amaze")}
        with rawfile.open("rb") as f:
            raw_sha = hashlib.file_digest(f, "sha256").hexdigest()
        wb, ccm = np.array(meta["wb"])[[0, 1, 3]], np.array(meta["ccm"]).reshape(3, 3)
        if meta["wb"][1] != meta["wb"][2]:
            raise ValueError("unequal greens require explicit upstream balancing")
        rois = json.loads((fixture/"rois.json").read_text())
        for name, (x, y, cw, ch) in rois.items():
            # Even alignment gives identical canonical lattice geometry to the full frame.
            halo = model.config.halo*2+4
            sx, sy = max(0, x-halo)//2*2, max(0, y-halo)//2*2
            ex, ey = min(w, (x+cw+halo+1)//2*2), min(h, (y+ch+halo+1)//2*2)
            reconstruction = reconstruct(model, np.array(raw[sy:ey, sx:ex]), meta["cfa"], (sx, sy))
            crop = reconstruction[y-sy:y-sy+ch, x-sx:x-sx+cw]
            images = {label: np.array(v[y:y+ch, x:x+cw]) for label, v in classical.items()}
            images["bnc_neural_reference"] = crop
            canvas = Image.new("RGB", (cw*3, ch+30), "#202020")
            draw = ImageDraw.Draw(canvas)
            for i, (label, rgb) in enumerate(images.items()):
                postccm = (rgb*wb) @ ccm.T
                rows.append(dict(capture=id, sensor=meta["sensor"], roi=name, route=label,
                    evidence="proxy / reference visual evidence, no real RGB ground truth",
                    finalizedBayerSHA256=raw_sha, post_demosaic=measure(rgb), post_WB_CCM=measure(postccm),
                    post_FLLF=None, final=None,
                    downstreamStatus="not run: standalone candidate fails gate; no production neural backend"))
                np.save(args.out/f"{id}-{name}-{label}.npy", rgb)
                canvas.paste(display(postccm), (i*cw, 30))
                draw.text((i*cw+4, 6), label, fill="white")
            canvas.save(args.out/f"{id}-{name}.png")
    (args.out/"real-raw-proxies.json").write_text(json.dumps(dict(packageSHA256=args.sha, rows=rows), indent=2))


if __name__ == "__main__":
    main()
