"""Deterministic full-FP32 reconstruction stress and valid scene-linear extremes."""
import argparse
import json
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

from ..checkpoint import load
from ..data import TrainingData, encode, read_corpus
from ..losses import reconstruction_loss
from ..procedural import scene
from .model import BoundedBncNeuralV2


def check(model, packed, truth, label):
    packed, truth = packed.cuda(non_blocking=True), truth.cuda(non_blocking=True)
    with torch.no_grad(), torch.autocast('cuda', enabled=False):
        output = model(packed.float())
        loss, terms, rgb = reconstruction_loss(packed.float(), output, truth.float(), True)
    for name, value in [('input', packed), ('target', truth), ('structured', output),
                        ('reconstructed', rgb), ('loss', loss), *terms.items()]:
        if value.dtype != torch.float32 or not bool(torch.isfinite(value).all()):
            raise FloatingPointError(f'{label}: {name}: dtype={value.dtype}, finite={bool(torch.isfinite(value).all())}')


def extremes(model):
    y, x = np.indices((256, 256))
    ramp = np.broadcast_to((x/255)[..., None], (256, 256, 3)).astype('f4')
    isoluminant, _ = scene(897163, 'train', 256, 823109, _kind_for_test='isoluminant_edge')
    color_edge = np.zeros((256, 256, 3), dtype='f4')
    color_edge[:, :128] = [1, .05, .05]
    color_edge[:, 128:] = [.05, 1, .05]
    cases = dict(black=np.zeros_like(ramp), near_black=ramp*1e-6,
                 maximum_headroom=np.full_like(ramp, 4), above_one=ramp*3.5,
                 neutral=ramp*2, saturated_chromatic=color_edge*4, isoluminant=isoluminant)
    sensor = np.array([[.84, .08, .08], [.08, .84, .08], [.08, .08, .84]], dtype='f4')
    for name, matrix in [('spectral_high', np.diag([2, .5, 2])@sensor*2),
                         ('spectral_low', np.diag([.5, 2, .5])@sensor*.25)]:
        if np.linalg.cond(matrix) > 5: raise ValueError('invalid spectral extreme')
        cases[name] = (ramp@matrix.T).astype('f4')
    count = 0
    for name, rgb in cases.items():
        if not (np.isfinite(rgb).all() and rgb.min() >= 0 and rgb.max() <= 4):
            raise ValueError('invalid scene-linear extreme: '+name)
        for pattern in ('RGGB', 'GRBG', 'GBRG', 'BGGR'):
            packed, truth = encode(rgb, pattern)
            check(model, packed.unsqueeze(0), truth.unsqueeze(0), name+'/'+pattern)
            count += 1
    return count


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=100000)
    parser.add_argument('--start-index', type=int, default=1000000)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)
    state = load(args.checkpoint)
    model = BoundedBncNeuralV2().cuda().eval()
    model.load_state_dict(state['model'], strict=True)
    parts, corpus = read_corpus(Path('build/bnc-neural-v2/corpus/corpus.json'))
    if corpus['sha256'] != state['datasetIdentity']['sha256']:
        raise ValueError('stress corpus identity changed')
    data = TrainingData(parts['train'], args.samples, 823109, args.start_index)
    loader = DataLoader(data, batch_size=32, shuffle=False, num_workers=args.workers,
                        pin_memory=True, persistent_workers=bool(args.workers),
                        prefetch_factor=2 if args.workers else None)
    count = 0
    for packed, truth in loader:
        check(model, packed, truth, f'sample indices {args.start_index+count}..{args.start_index+count+len(packed)-1}')
        count += len(packed)
        if count % 5000 == 0:
            print(json.dumps(dict(samplesChecked=count, lastIndex=args.start_index+count-1)), flush=True)
    extreme_count = extremes(model)
    result = dict(passed=True, samples=count, startIndex=args.start_index,
                  lastIndex=args.start_index+count-1, extremeCases=extreme_count,
                  cfaPatterns=['RGGB','GRBG','GBRG','BGGR'], forwardAndLoss='FP32',
                  checkpointStep=state['step'])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result), flush=True)


if __name__ == '__main__': main()
