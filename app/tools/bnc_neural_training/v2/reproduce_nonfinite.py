"""Replay a committed W24 checkpoint without writing or advancing the run."""
import argparse
import json
from pathlib import Path

import torch
from torch.utils.data import DataLoader

from ..losses import rgb_from_missing
from .checkpoint import GlobalCosine, capture, load, restore_rng, save
from .data import TrainingData, read_corpus
from .losses import missing_targets, reconstruction_loss
from .model import BncNeuralV2, missing_rgb
from .procedural import scene


def summary(value):
    value = value.detach().float()
    finite = torch.isfinite(value)
    good = value[finite]
    return dict(finite=bool(finite.all()), min=float(good.min()) if good.numel() else None,
                max=float(good.max()) if good.numel() else None,
                absMax=float(good.abs().max()) if good.numel() else None,
                nonfinite=int((~finite).sum()))


def inspect_batch(model, packed, truth):
    rows = {"modelInput": summary(packed), "sameSignalTarget": summary(truth)}
    hooks = []
    for name in ("stem", "shared", "green", "opponent_input", "opponent", "opponent_output"):
        module = model.get_submodule(name)
        hooks.append(module.register_forward_hook(
            lambda _module, _inputs, output, key=name: rows.__setitem__(key, summary(output))))
    with torch.no_grad():
        with torch.autocast("cuda", dtype=torch.float16):
            output = model(packed)
        rows["structuredOutput"] = summary(output)
        predicted_missing = missing_rgb(packed, output)
        rows["predictedMissingRGB"] = summary(predicted_missing)
        rows["sameSignalReference"] = summary(missing_targets(truth))
        rows["individualErrorTensor"] = summary(predicted_missing - missing_targets(truth))
        rows["directRGBMerge"] = summary(rgb_from_missing(packed.float(), predicted_missing))
        loss, terms, rgb = reconstruction_loss(packed, output, truth, True)
        rows["reconstructedRGB"] = summary(rgb)
        rows["lossTerms"] = {key: summary(value) for key, value in terms.items()}
        rows["sameSignalLoss"] = summary(loss)
    for hook in hooks:
        hook.remove()
    return rows


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--failure-state", type=Path)
    parser.add_argument("--max-steps", type=int, default=300)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--save-failure", type=Path)
    parser.add_argument("--save-resumable-failure", type=Path)
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)
    if args.failure_state:
        state = torch.load(args.failure_state, map_location="cpu", weights_only=True)
        model = BncNeuralV2().cuda().eval()
        model.load_state_dict(state["model"])
        index = state["sampleIndex"]
        packed, truth = TrainingData([], 1, state["seed"], index)[0]
        _, meta = scene(index, "train", 256, state["seed"])
        report = dict(step=state["step"], sampleIndex=index, seed=state["seed"],
                      datasetSource="procedural", scene=meta,
                      spectralMixingMatrix=None,
                      trace=inspect_batch(model, packed.unsqueeze(0).cuda(), truth.unsqueeze(0).cuda()))
        print(json.dumps(report, indent=2), flush=True)
        return
    if not args.checkpoint:
        parser.error("--checkpoint or --failure-state is required")
    state = load(args.checkpoint)
    parts, _ = read_corpus(Path("build/bnc-neural-v2/corpus/corpus.json"))
    model = BncNeuralV2().cuda()
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, betas=(.9, .99), weight_decay=1e-4)
    scaler = torch.amp.GradScaler("cuda")
    scheduler = GlobalCosine(optimizer)
    model.load_state_dict(state["model"])
    optimizer.load_state_dict(state["optimizer"])
    scaler.load_state_dict(state["scaler"])
    scheduler.load_state_dict(state["scheduler"])
    step = state["step"]
    generator = torch.Generator().manual_seed(823109 + 7919)
    if state["sampler"]["loaderGeneratorState"] is not None:
        generator.set_state(state["sampler"]["loaderGeneratorState"])
    generator_before = generator.get_state()
    data = TrainingData(parts["train"], args.max_steps * 32, 823109, step * 32)
    loader = iter(DataLoader(data, batch_size=32, shuffle=False, num_workers=args.workers,
                             pin_memory=True, persistent_workers=bool(args.workers),
                             prefetch_factor=2 if args.workers else None, generator=generator))
    generator.set_state(generator_before)
    restore_rng(state["rng"])
    model.train()
    try:
        for _ in range(args.max_steps):
            packed, truth = next(loader)
            packed, truth = packed.cuda(non_blocking=True), truth.cuda(non_blocking=True)
            for attempt in range(12):
                optimizer.zero_grad(set_to_none=True)
                scheduler.apply(step)
                with torch.autocast("cuda", dtype=torch.float16):
                    output = model(packed)
                loss, terms, _ = reconstruction_loss(packed, output, truth, True)
                if not torch.isfinite(loss):
                    if args.save_resumable_failure:
                        destination = args.save_resumable_failure
                        if destination.exists():
                            raise FileExistsError(destination)
                        saved_args = argparse.Namespace(**state["trainingArguments"])
                        # scheduler.apply(step) ran for the failed batch, but no
                        # optimizer update was committed. Restore the prior LR
                        # before capturing the boundary at next_step=step.
                        attempted_lrs = [group["lr"] for group in optimizer.param_groups]
                        for group in optimizer.param_groups:
                            group["lr"] = GlobalCosine.value(step - 1)
                        captured = capture(model, optimizer, scaler, scheduler, step,
                                           state["history"], state["best"], state["provenance"],
                                           state["elapsed"], saved_args, generator)
                        captured["checkpointBoundary"] = (
                            "after committed optimizer step; failed batch was not updated "
                            "and must be replayed from indexed sampler")
                        captured["diagnosticFailure"] = dict(step=step,
                            sampleIndices=list(range(step * 32, (step + 1) * 32)),
                            reason="nonfinite same-signal reconstruction loss",
                            attemptedLR=attempted_lrs)
                        save(destination, captured)
                        for group, attempted_lr in zip(optimizer.param_groups, attempted_lrs):
                            group["lr"] = attempted_lr
                    if args.save_failure:
                        if args.save_failure.exists():
                            raise FileExistsError(args.save_failure)
                        args.save_failure.parent.mkdir(parents=True, exist_ok=True)
                        failing = next((i for i in range(32) if not bool(torch.isfinite(output[i]).all())), None)
                        if failing is None:
                            with torch.no_grad():
                                failing = next((i for i in range(32)
                                                if not bool(torch.isfinite(reconstruction_loss(
                                                    packed[i:i+1], output[i:i+1], truth[i:i+1], True)[0]))), None)
                        torch.save(dict(model={k: v.detach().cpu() for k, v in model.state_dict().items()},
                                        step=step, sampleIndex=step * 32 + failing if failing is not None else None,
                                        seed=823109,
                                        sourceCheckpoint=str(args.checkpoint)), args.save_failure)
                    report = dict(step=step, sampleIndices=list(range(step * 32, (step + 1) * 32)),
                                  learningRate=optimizer.param_groups[0]["lr"],
                                  scalerScale=scaler.get_scale(), attempt=attempt,
                                  perSampleOutput=[summary(output[i]) for i in range(32)],
                                  perSampleInput=[summary(packed[i]) for i in range(32)],
                                  trace=inspect_batch(model, packed, truth))
                    print(json.dumps(report, indent=2), flush=True)
                    return
                scaler.scale(loss).backward()
                scale = scaler.get_scale()
                scaler.step(optimizer)
                scaler.update()
                if scaler.get_scale() >= scale:
                    break
            else:
                raise FloatingPointError("AMP repeatedly overflowed")
            step += 1
            scheduler.committed()
            if step % 10 == 0:
                print(json.dumps(dict(step=step, loss=float(loss), lr=optimizer.param_groups[0]["lr"],
                                      scale=scaler.get_scale())), flush=True)
        print(json.dumps(dict(noFailureThroughStep=step)), flush=True)
    finally:
        if hasattr(loader, "_shutdown_workers"):
            loader._shutdown_workers()


if __name__ == "__main__":
    main()
