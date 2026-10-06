"""Train from a reviewed full-RGB manifest; export research weights, never activate.

python -m app.tools.bnc_neural_training.train --manifest corpus.json --out run --steps 50000
Validation is fixed and group-disjoint. Held-out tests are not consulted in training.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import random
import shutil
import time
import numpy as np
import torch
from .contracts import pack, sample, targets
from .corpus import load_manifest, read_linear, augment_camera, digest
from .losses import DEFAULT_WEIGHTS, reconstruction_loss
from .model import BncNeural, Config
from .package import export_reference


def code_hash():
    h = hashlib.sha256()
    for path in sorted(Path(__file__).parent.glob("*.py")):
        h.update(path.name.encode())
        h.update(path.read_bytes())
    return h.hexdigest()


def tensor(a, device):
    return torch.from_numpy(np.ascontiguousarray(a.transpose(2, 0, 1))).unsqueeze(0).to(device)


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--width", type=int, default=12)
    p.add_argument("--blocks", type=int, default=6)
    p.add_argument("--steps", type=int, default=50000)
    p.add_argument("--seed", type=int, default=73109)
    p.add_argument("--patch", type=int, default=96)
    p.add_argument("--device", default="cpu")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--ablation", choices=("full", "missing_only", "no_opponent"), default="full")
    p.add_argument("--neutral-probability", type=float, default=0.)
    p.add_argument("--lr", type=float, default=.001)
    p.add_argument("--decay-lr", action="store_true")
    args = p.parse_args()
    if args.steps < 1 or args.patch < 32 or args.patch % 2 or not 0 <= args.neutral_probability <= 1 or args.lr <= 0:
        p.error("positive steps and even patch >=32 required")
    records = list(load_manifest(args.manifest))
    splits = {s: [r for r in records if r["split"] == s] for s in ("train", "validation", "test")}
    # Resource guard, NOT a certificate of corpus sufficiency or model quality.
    if any(len({r['source_group'] for r in splits[s]}) < n for s, n in
           (("train", 100), ("validation", 10), ("test", 10))):
        raise ValueError("BNC_NEURAL_CORPUS_INSUFFICIENT: need >=100/10/10 independent source groups")
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out/"source").mkdir()
    for file in Path(__file__).parent.glob("*.py"):
        shutil.copy2(file, args.out/"source"/file.name)
    random.seed(args.seed)
    rng = np.random.default_rng(args.seed)
    torch.manual_seed(args.seed)
    torch.set_num_threads(args.threads)
    torch.use_deterministic_algorithms(True)
    model = BncNeural(Config(args.width, args.blocks)).to(args.device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0)
    weights = dict(DEFAULT_WEIGHTS)
    if args.ablation == "missing_only":
        weights = dict(missing=1.)
    elif args.ablation == "no_opponent":
        weights = dict(missing=1., luma_gradient=.2, detail=.1)
    # Only train images are cached; never load the test split here.
    cache = {}
    validation = []
    for record in splits["validation"]:
        rgb = read_linear(record)
        if min(rgb.shape[:2]) < args.patch:
            raise ValueError("validation image smaller than patch")
        y, x = (rgb.shape[0]-args.patch)//2, (rgb.shape[1]-args.patch)//2
        rgb = rgb[y:y+args.patch, x:x+args.patch]
        raw = sample(rgb)
        packed, g = pack(raw)
        validation.append((tensor(packed, args.device), tensor(targets(rgb, g), args.device)))
    history, start, best = [], time.perf_counter(), float("inf")
    provenance = dict(modelVersion=f"research-w{args.width}-b{args.blocks}-seed{args.seed}-{args.ablation}",
                      trainingCorpus={"manifest": str(args.manifest), "sha256": digest(args.manifest),
                                      "counts": {s: len(v) for s, v in splits.items()}},
                      splitProvenance="source_group-disjoint; SHA-verified; held-out test not loaded",
                      trainingCodeSHA256=code_hash(), lossDefinition=weights,
                      exportDate=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      trainingSteps=0, purpose="research_candidate", seed=args.seed,
                      command={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                      torchVersion=str(torch.__version__), noNoiseObjective=True,
                      objectiveVersion="opponent-v2", neutralProbability=args.neutral_probability,
                      augmentation="positive row mixing [0,.08], gains [.5,2], exposure [.25,2], domain rejection")
    for step in range(1, args.steps + 1):
        record = splits["train"][int(rng.integers(len(splits["train"])))]
        key = record["path"]
        if key not in cache:
            if len(cache) >= 16:
                cache.pop(next(iter(cache)))
            cache[key] = read_linear(record)
        image = cache[key]
        if min(image.shape[:2]) < args.patch:
            raise ValueError("train image smaller than patch")
        y = int(rng.integers(image.shape[0] - args.patch + 1))
        x = int(rng.integers(image.shape[1] - args.patch + 1))
        rgb = np.rot90(image[y:y+args.patch, x:x+args.patch], int(rng.integers(4)))
        if rng.random() < .5:
            rgb = rgb[:, ::-1]
        rgb, _ = augment_camera(rgb, rng)
        if args.neutral_probability and rng.random() < args.neutral_probability:
            # Same-signal neutral reconstruction, never a neutralized prediction.
            rgb = np.repeat(rgb.mean(-1, keepdims=True), 3, axis=-1)
        # CFA augmentation via four phases; the model still sees canonical RGGB geometry.
        pattern = ("RGGB", "GRBG", "GBRG", "BGGR")[int(rng.integers(4))]
        raw = sample(rgb, pattern)
        packed, g = pack(raw, pattern)
        inp, truth = tensor(packed, args.device), tensor(targets(rgb, g), args.device)
        model.train()
        if args.decay_lr:
            optimizer.param_groups[0]["lr"] = args.lr*(.1+.9*.5*(1+np.cos(np.pi*(step-1)/args.steps)))
        optimizer.zero_grad(set_to_none=True)
        loss, _ = reconstruction_loss(inp, model(inp), truth, weights)
        if not torch.isfinite(loss):
            raise ValueError("nonfinite training loss")
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.)
        optimizer.step()
        if step % 250 == 0 or step == args.steps:
            model.eval()
            with torch.inference_mode():
                results = [(model(a), a, b) for a,b in validation]
                val = sum(float(reconstruction_loss(a, pred, b, weights)[0]) for pred,a,b in results) / len(validation)
                val_missing = sum(float((pred-b).abs().mean()) for pred,a,b in results) / len(validation)
            row = dict(step=step, loss=float(loss.detach()), validation=val,
                       validation_missing_l1=val_missing, seconds=time.perf_counter()-start)
            history.append(row)
            print(json.dumps(row), flush=True)
            if val_missing < best:
                best = val_missing
                provenance["trainingSteps"] = step
                provenance["exportDate"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                provenance["selection"] = "lowest fixed-validation missing-channel L1; no held-out metrics"
                export_reference(model, args.out / "candidate.bncmodel", provenance)
            (args.out / "history.json").write_text(json.dumps(history, indent=2))
    (args.out / "run.json").write_text(json.dumps(dict(provenance, attemptedSteps=args.steps), indent=2))


if __name__ == "__main__":
    main()
