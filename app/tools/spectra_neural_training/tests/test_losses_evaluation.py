import torch

from spectra_train.evaluation import evaluate_prediction, new_frequency_energy, repeatability_error
from spectra_train.losses import LossWeights, TeacherLossBatch, compute_teacher_loss
from spectra_train.model_teacher import PhysicsConditionedTeacher, TeacherConfig


def _small_model_and_batch():
    torch.manual_seed(2)
    cfg = TeacherConfig(widths=(16,24,32,48), encoder_blocks=(1,1,1), bottleneck_blocks=1, decoder_blocks=(1,1,1))
    model = PhysicsConditionedTeacher(cfg)
    b, h, w = 2, 24, 24
    clean = torch.rand(b, 4, h, w) * 0.7
    noisy = (clean + torch.randn_like(clean) * 0.02).clamp(0,1)
    sigma = torch.full_like(clean, 0.02)
    cond = torch.cat((noisy, torch.log(sigma), torch.ones_like(clean), torch.full((b,1,h,w),0.9), torch.ones(b,1,h,w)), dim=1)
    global_c = torch.zeros(b, 18)
    output = model(cond, global_c)
    batch = TeacherLossBatch(clean, noisy, sigma.square(), torch.ones_like(clean), torch.tensor([True, False]))
    return model, cfg, cond, output, batch


def test_multiobjective_loss_is_finite_and_backpropagates():
    model, cfg, cond, output, batch = _small_model_and_batch()
    total, terms = compute_teacher_loss(output, batch, LossWeights(frequency=0.01))
    assert torch.isfinite(total)
    assert set(("raw","gradient","multiscale","frequency","identity","physics","channel_bias","uncertainty","isp_proxy","total")) <= set(terms)
    total.backward()
    assert any(p.grad is not None and torch.isfinite(p.grad).all() for p in model.parameters())


def test_evaluation_contains_bias_detail_noise_posterior_and_safety():
    _, cfg, cond, output, batch = _small_model_and_batch()
    m = evaluate_prediction(output, cond, batch.noisy, batch.clean, cfg.residual_k_sigma)
    required = {
        "psnr", "mae", "mean_bias_R", "mean_bias_G1", "mean_bias_G2", "mean_bias_B",
        "gradient_error", "high_frequency_energy_ratio", "residual_variance",
        "posterior_fraction_within_1sigma", "correction_sigma_p99", "fraction_gt_3sigma",
        "clipped_cells_touched_fraction", "new_frequency_energy_vs_gt",
    }
    assert required <= set(m)
    assert all(torch.isfinite(torch.tensor(v)) for v in m.values())


def test_flat_field_new_frequency_metric_detects_added_structure():
    flat = torch.ones(1,4,32,32) * 0.4
    clean_score = new_frequency_energy(flat, flat)
    striped = flat.clone()
    striped[..., ::2] += 0.01
    assert clean_score == 0.0
    assert new_frequency_energy(striped, flat) > clean_score


def test_repeatability_exact_zero_for_identical_output():
    x = torch.rand(1,4,8,8)
    assert repeatability_error(x, x.clone()) == 0.0
