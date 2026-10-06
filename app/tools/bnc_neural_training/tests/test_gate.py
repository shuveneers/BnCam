import copy
import unittest
from ..evaluate import quality_gate


class GateTests(unittest.TestCase):
    def clean_rows(self):
        q = dict(opponent_rms=.001, luma_retention=1., luma_gradient_retention=1., sampled_sensel_max_error=0.)
        rows = {name: dict(bnc_neural=copy.copy(q)) for name in
                ("two_pixel_vertical", "two_pixel_horizontal", "frequency_sweep", "diagonal_edge", "fine_repetitive")}
        for name in ("color_edge", "isoluminant_color_edge"):
            rows[name] = dict(bnc_neural=dict(opponent_channels={c: dict(chromatic_gradient_retention=1.) for c in ("RG","BG")}))
        return rows

    def test_absent_model_is_failure_not_fallback_quality(self):
        self.assertFalse(quality_gate({})["passed"])

    def test_cannot_hide_failed_period8_in_average(self):
        rows = self.clean_rows()
        rows["fine_repetitive"]["bnc_neural"]["opponent_rms"] = .01122
        self.assertFalse(quality_gate(rows)["passed"])

    def test_blur_cannot_pass_as_color_improvement(self):
        rows = self.clean_rows()
        rows["two_pixel_vertical"]["bnc_neural"].update(opponent_rms=0., luma_gradient_retention=.5)
        self.assertFalse(quality_gate(rows)["passed"])

    def test_synthetic_pass_is_never_product_activation(self):
        gate = quality_gate(self.clean_rows())
        self.assertTrue(gate["passed"])
        self.assertFalse(gate["productReady"])

    def test_desaturated_isoluminant_edge_is_rejected(self):
        rows = self.clean_rows()
        rows["isoluminant_color_edge"]["bnc_neural"]["opponent_channels"]["RG"]["chromatic_gradient_retention"] = .5
        self.assertFalse(quality_gate(rows)["passed"])
