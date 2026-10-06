"""Preserve the already selected validation RGB scenes across the palette fix."""
from pathlib import Path
import hashlib
import json
import numpy as np
from .checkpoint import objective_identity
from .data import validation_cases,encode

PREVIOUS_PROCEDURAL_SHA='6c8972e678a894a4615dcf3dfac07553ebba0538ec0ac7c21d1fbcfdb183c9e4'
RELIABILITY_FIX_SHA='938ec060dff8b936e79b8b19336ba286fe9ee11e773cfa23eae682b0c95b9c7f'
PREVIOUS_MODEL_SHA='2d80a631768e9d398fe97c1024c524bd5d9a4ac8d1b2b91e756324f328961f98'
FP32_OPPONENT_SHA='b6999a3624c30a6e5afdc6b62ead892eddc903f5cf368075ae9ab7a9e834b71f'


def adopt_precision_fix(state):
    """Permit only the audited FP16-overflow repair; preserve the source checkpoint."""
    current=objective_identity();old=state['objectiveIdentity']
    if old==current:return state
    changed=[name for name in current if old.get(name)!=current[name]]
    if changed!=['v2/model.py'] or old['v2/model.py']!=PREVIOUS_MODEL_SHA or current['v2/model.py']!=FP32_OPPONENT_SHA:
        raise ValueError('architecture/loss/data changed outside the audited opponent FP32 repair')
    state['objectiveIdentity']=current
    state['provenance']['opponentPrecisionMigration']=dict(oldSHA256=PREVIOUS_MODEL_SHA,
        newSHA256=FP32_OPPONENT_SHA,
        scope='FP32 arithmetic for gated opponent branch under AMP; unchanged parameters and FP32 inference')
    return state


def adopt_generator_fix(state,original_run):
    """Explicit one-file migration; never edits the original on-disk checkpoint."""
    current=objective_identity();old=state['objectiveIdentity']
    if old==current:return state
    changed=[name for name in current if old.get(name)!=current[name]]
    if changed!=['v2/procedural.py'] or old['v2/procedural.py']!=PREVIOUS_PROCEDURAL_SHA or current['v2/procedural.py']!=RELIABILITY_FIX_SHA:
        raise ValueError('architecture/loss/data changed outside the approved procedural palette repair')
    meta_path=Path(original_run)/'fixed-validation-procedural.json';metadata=json.loads(meta_path.read_text())
    archive=Path(metadata['archive'])
    if (metadata['sourceSHA256']!=PREVIOUS_PROCEDURAL_SHA or metadata['seed']!=state['trainingArguments']['seed']
            or hashlib.sha256(archive.read_bytes()).hexdigest()!=metadata['sha256']):
        raise ValueError('fixed pre-fix validation truth changed')
    state['objectiveIdentity']=current
    state['provenance']['generatorReliabilityMigration']=dict(oldSHA256=PREVIOUS_PROCEDURAL_SHA,
        newSHA256=RELIABILITY_FIX_SHA,validationArchive=metadata['archive'],validationArchiveSHA256=metadata['sha256'],
        scope='constructive isoluminant palette only; fixed validation preserved')
    return state


def cases(records,seed,provenance):
    migration=provenance.get('generatorReliabilityMigration')
    if not migration:return validation_cases(records,seed=seed)
    archive=Path(migration['validationArchive'])
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=migration['validationArchiveSHA256']:
        raise ValueError('fixed validation archive SHA mismatch')
    metadata=json.loads(archive.with_name('fixed-validation-procedural.json').read_text())
    if metadata['seed']!=seed or metadata['sourceSHA256']!=PREVIOUS_PROCEDURAL_SHA:
        raise ValueError('validation archive identity mismatch')
    natural=validation_cases(records,seed=seed,procedural_count=0)
    labels=json.loads((archive.parent/'validation-manifest.json').read_text())['cases']
    if len(labels)!=len(natural)+52 or labels[:len(natural)]!=[c[0] for c in natural]:
        raise ValueError('natural validation selection changed')
    first_procedural=len(natural)
    with np.load(archive,allow_pickle=False) as content:
        if set(content.files)!={f'rgb_{i:02d}' for i in range(52)}:raise ValueError('validation scene table incomplete')
        for i in range(52):
            label=labels[first_procedural+i]
            if not label.startswith(f'analytic:validation:{i}:'):raise ValueError('validation scene label mismatch')
            rgb=content[f'rgb_{i:02d}']
            if rgb.shape!=(256,256,3) or rgb.dtype!=np.float32 or not np.isfinite(rgb).all() or rgb.min()<0 or rgb.max()>4:
                raise ValueError('invalid fixed validation scene')
            a,b=encode(rgb,'BGGR');natural.append((label,a,b))
    return natural
