"""Record zero-update eligibility before any optimizer updates."""
import json

from ..train import atomic_json
from .distribution import ROOT
from .phase_a import TAUS


def main():
    source=json.loads((ROOT/'source-zero-update.json').read_text())
    cases=('thin_lines','two_pixel_horizontal','two_pixel_vertical','frequency_sweep',
           'diagonal_edge','slanted_edge_0417','slanted_edge_131','fine_repetitive',
           'zero_chroma_fine_detail','color_edge','isoluminant_color_edge')
    result={}
    for tau in TAUS:
        candidate=json.loads((ROOT/f'tau{tau}/zero-update.json').read_text())
        changes=[]
        for name in cases:
            before=source['synthetic'][name]['bnc_neural']
            after=candidate['synthetic'][name]['bnc_neural']
            for key in ('missing_rgb_l1','missing_g_l1','opponent_error_rms','opponent_rms',
                        'luma_gradient_retention'):
                a,b=before[key],after[key]
                if a is not None and b is not None:
                    changes.append(dict(case=name,metric=key,absoluteDelta=abs(b-a),
                                        relativeDelta=abs(b-a)/max(abs(a),1e-12)))
            for channel in ('RG','BG'):
                a=before['opponent_channels'][channel]['chromatic_gradient_retention']
                b=after['opponent_channels'][channel]['chromatic_gradient_retention']
                if a is not None and b is not None:
                    changes.append(dict(case=name,metric=channel+' edge retention',
                                        absoluteDelta=abs(b-a),relativeDelta=abs(b-a)/max(abs(a),1e-12)))
        largest=max(changes,key=lambda x:x['absoluteDelta'])
        # Fixed gates retain their original thresholds. This screen only asks
        # whether changing the gate with unchanged weights has a material effect:
        # less than 1% of a baseline error metric, and <0.001 absolute change
        # in gradient-retention ratios (far inside the existing 0.95..1.05 gate).
        material=[]
        for entry in changes:
            if 'retention' in entry['metric']:
                if entry['absoluteDelta']>=.001:material.append(entry)
            elif entry['relativeDelta']>=.01:
                material.append(entry)
        score=candidate['validation']['aggregate']['selectionScore']
        source_score=source['validation']['aggregate']['selectionScore']
        numerically_eligible=(candidate['numericalFailure'] is None and
                              candidate['failedSampleLoss'] is not None and
                              candidate['maxGradient'] is not None)
        eligible=numerically_eligible and not material and abs(score-source_score)<.0001
        result[str(tau)]=dict(tau=tau,eligibleFor200Steps=eligible,
                               zeroUpdateScore=score,sourceZeroUpdateScore=source_score,
                               failedSampleFinite=numerically_eligible,
                               largestAbsoluteFixtureChange=largest,
                               materialRegressions=material,
                               changedNormalValuesOver1Percent=candidate['normalGateDifference']['over1Percent'])
    atomic_json(ROOT/'phase-a-screen.json',result)
    print(json.dumps(result,indent=2),flush=True)


if __name__=='__main__':main()
