import copy
import unittest
import torch
import numpy as np
from ..evaluate import synthetic_gate, REQUIRED, PERIODS, DETAIL
from ..model import BncNeuralV2, MissingRgbAdapter
from ...inspect_candidate import reconstruct
from ..train import validate


def passing_rows():
    q=dict(opponent_rms=.001,opponent_error_rms=.001,luma_gradient_retention=1.,sampled_sensel_max_error=0.,
        opponent_channels={c:dict(chromatic_gradient_retention=1.) for c in ('RG','BG')})
    return {n:dict(bnc_neural=copy.deepcopy(q),malvar=copy.deepcopy(q),amaze=copy.deepcopy(q))
        for n in REQUIRED+PERIODS+DETAIL+('flat_neutral','color_edge','isoluminant_color_edge')}


class GateTests(unittest.TestCase):
    def test_shared_reference_helper_understands_signed_v2_package_outputs(self):
        model=BncNeuralV2().eval();raw=np.random.default_rng(18).uniform(0,4,(34,38)).astype('f4')
        np.testing.assert_array_equal(reconstruct(model,raw),reconstruct(MissingRgbAdapter(model),raw))

    def test_neutral_only_validation_keeps_undefined_ratios_explicit(self):
        rows,aggregate=validate(BncNeuralV2(),[('neutral',torch.full((4,8,8),.1),torch.full((3,16,16),.1))],'cpu',True)
        self.assertIsNone(rows[0]['opponent_gradient_ratio'])
        self.assertEqual(aggregate['perCaseExtrema']['opponent_gradient_ratio'],dict(min=None,max=None))

    def test_all_requested_limits_and_missing_evidence_fail_closed(self):
        rows=passing_rows();self.assertTrue(synthetic_gate(rows)['passed'])
        for name,key,value in (('two_pixel_horizontal','opponent_rms',.005001),
            ('flat_neutral','opponent_rms',.001001),('diagonal_edge','luma_gradient_retention',.97999),
            ('frequency_sweep','sampled_sensel_max_error',1e-9)):
            bad=copy.deepcopy(rows);bad[name]['bnc_neural'][key]=value
            self.assertFalse(synthetic_gate(bad)['passed'])
        del rows['flat_neutral'];self.assertFalse(synthetic_gate(rows)['passed'])

    def test_neutralization_cannot_pass_and_periods_compare_both_classics(self):
        rows=passing_rows();rows['isoluminant_color_edge']['bnc_neural']['opponent_channels']['BG']['chromatic_gradient_retention']=.94
        self.assertFalse(synthetic_gate(rows)['passed'])
        rows=passing_rows();rows['repetitive_period_5']['amaze']['opponent_error_rms']=.01
        rows['repetitive_period_5']['bnc_neural']['opponent_error_rms']=.002
        self.assertFalse(synthetic_gate(rows)['passed'])


if __name__=='__main__':unittest.main()
