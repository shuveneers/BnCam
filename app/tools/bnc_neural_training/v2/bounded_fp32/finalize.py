"""Freeze the bounded-FP32 qualification after all comparisons and stress checks."""
import hashlib
import json
from pathlib import Path

from .train import SOURCE, OUT


def main():
    report_dir = OUT/'sessions/step_032555'
    comparison = json.loads((report_dir/'comparison.json').read_text())
    stress = json.loads((OUT/'stress.json').read_text())
    numerics = json.loads((OUT/'numerics.json').read_text())
    status = json.loads((OUT/'status.json').read_text())
    checkpoint = OUT/'checkpoints/latest.pt'
    digest = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    if digest != checkpoint.with_suffix('.pt.sha256').read_text().strip():
        raise ValueError('final checkpoint SHA mismatch')
    if not stress['passed'] or stress['samples'] != 100000 or stress['extremeCases'] != 36:
        raise ValueError('requested stability coverage incomplete')
    if status['currentStep'] != 32555 or numerics['optimizerStep'] != 32555:
        raise ValueError('qualification did not stop at exact target')
    if 'synthetic' not in comparison['routes']['boundedAmpPrototype']:
        raise ValueError('bounded-AMP comparator missing synthetic evidence')
    safe = bool(not comparison['qualityRegression'] and numerics['numericalFailures'] == 0
                and numerics['adamWStateFinite'] and stress['passed']
                and comparison['syntheticGate32k']['passed'])
    result = dict(fork='research/bnc-v2-bounded-gate-fp32',
                  startingCheckpoint=str(SOURCE.resolve()), startStep=31555, endStep=32555,
                  resumableCheckpoint=str(checkpoint.resolve()), checkpointSHA256=digest,
                  boundedGateInstalled=True, fullFP32Forward=True,
                  numericalFailures=numerics['numericalFailures'],
                  maxAbsSharedFeature=numerics['maxAbsSharedFeature'],
                  maxAbsGreenOutput=numerics['maxAbsGreenOutput'],
                  maxAbsOpponentOutput=numerics['maxAbsOpponentOutput'],
                  maxRawGateProduct=numerics['maxRawGateProduct'],
                  maxBoundedGate=numerics['maxBoundedGate'],
                  maxGradient=numerics['maxGradient'],
                  adamWStateFinite=numerics['adamWStateFinite'],
                  validation31k=comparison['validation31k']['selectionScore'],
                  validation32k=comparison['validation32k']['selectionScore'],
                  boundedAmpValidation32k=comparison['routes']['boundedAmpPrototype']['step32555'],
                  syntheticGateFailures=len(comparison['syntheticGate32k']['failures']),
                  boundedAmpSyntheticGateFailures=len(comparison['routes']['boundedAmpPrototype']['syntheticGate']['failures']),
                  qualityRegression=comparison['qualityRegression'],
                  safeToContinueTo55k=safe,
                  commandToContinue=status['commandToContinue'] if safe else 'none; quality regression',
                  deterministicSamplesChecked=stress['samples'], extremeCasesChecked=stress['extremeCases'],
                  safeToPowerOff=True)
    (report_dir/'qualification.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    status.update(safeToContinueTo55k=safe, commandToContinue=result['commandToContinue'],
                  nextCommand=result['commandToContinue'], stressTestPassed=True)
    (OUT/'status.json').write_text(json.dumps(status, indent=2)+'\n', encoding='utf-8')
    report = report_dir/'report.md'
    appendix = ['','## Stability and decision','',
                f"100,000 deterministic training samples (indices {stress['startIndex']}–{stress['lastIndex']}) and "
                f"{stress['extremeCases']} extreme valid cases across all four CFA patterns passed in FP32.",
                f"Peak |shared| {numerics['maxAbsSharedFeature']:.6f}; |green| {numerics['maxAbsGreenOutput']:.6f}; "
                f"|opponent| {numerics['maxAbsOpponentOutput']:.6f}; raw gate product {numerics['maxRawGateProduct']:.6f}; "
                f"bounded gate {numerics['maxBoundedGate']:.6f}; gradient {numerics['maxGradient']:.6f}. AdamW finite.",
                f"Quality regression: {comparison['qualityRegression']}; continue to 55k: {safe}. "
                'The fork remains research-only and product availability is unchanged.','']
    old = report.read_text(encoding='utf-8')
    if '## Stability and decision' not in old:
        report.write_text(old+'\n'.join(appendix), encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__': main()
