"""Exercise indexed W24 training data through AMP reconstruction without updates."""
import argparse
import json
from pathlib import Path
import types

import torch
from torch.utils.data import DataLoader

from .data import TrainingData, read_corpus
from .losses import reconstruction_loss
from .model import BncNeuralV2, GatedBlock


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--model-state", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=100000)
    parser.add_argument("--start-index", type=int, default=850000)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--experimental-gate-cap", type=float)
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)
    parts, _ = read_corpus(Path("build/bnc-neural-v2/corpus/corpus.json"))
    model = BncNeuralV2().cuda().eval()
    model.load_state_dict(torch.load(args.model_state, map_location="cpu", weights_only=True)["model"])
    if args.experimental_gate_cap is not None:
        if args.experimental_gate_cap <= 0:
            parser.error("experimental gate cap must be positive")
        cap = args.experimental_gate_cap

        def bounded(self, x):
            a, b = self.expand(self.depthwise(x)).chunk(2, dim=1)
            gate = cap * torch.tanh((a.float() * b.float()) / cap)
            delta = self.project(gate)
            return x + torch.tanh(self.alpha).to(delta.dtype) * delta

        for module in model.modules():
            if isinstance(module, GatedBlock):
                module.forward = types.MethodType(bounded, module)
    data = TrainingData(parts["train"], args.samples, 823109, args.start_index)
    loader = DataLoader(data, batch_size=32, shuffle=False, num_workers=args.workers,
                        pin_memory=True, persistent_workers=bool(args.workers),
                        prefetch_factor=2 if args.workers else None)
    completed = 0
    with torch.no_grad():
        for packed, truth in loader:
            packed, truth = packed.cuda(non_blocking=True), truth.cuda(non_blocking=True)
            with torch.autocast("cuda", dtype=torch.float16):
                output = model(packed)
            loss, terms, rgb = reconstruction_loss(packed, output, truth, True)
            for key, value in (("packed", packed), ("truth", truth), ("structuredOutput", output),
                               ("reconstructedRGB", rgb), ("loss", loss), *terms.items()):
                if not bool(torch.isfinite(value).all()):
                    raise FloatingPointError(f"{key} nonfinite at indices "
                                             f"{list(range(args.start_index + completed, args.start_index + completed + len(packed)))}")
            completed += len(packed)
            if completed % 5000 == 0:
                print(json.dumps(dict(completed=completed, startIndex=args.start_index,
                                      lastIndex=args.start_index + completed - 1)), flush=True)
    print(json.dumps(dict(passed=True, samples=completed, startIndex=args.start_index,
                          finalIndex=args.start_index + completed - 1,
                          dataset="deterministic indexed 60% natural / 40% procedural",
                          experimentalGateCap=args.experimental_gate_cap)), flush=True)


if __name__ == "__main__":
    main()
