from pathlib import Path
root = Path(__file__).resolve().parents[1]
shader = (root/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
backend = (root/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp').read_text()
checks = {
    'two_explicit_residual_domains': 'writeResidualCandidate(uvec2 sampleGid, bool postColourDomain)' in shader,
    'demosaic_domain_reads_output': 'postColourDomain ? colorRgbAt(x, y) : residentRgbAt(x, y)' in shader,
    'mode4_is_post_demosaic': 'writeResidualCandidate(gid, false); // POST_DEMOSAIC_RGB' in shader,
    'mode11_is_post_colour': 'writeResidualCandidate(gid, true); // POST_COLOUR_TRANSFORM_RGB' in shader,
    'backend_demosaic_dispatch_mode4': backend.count('push.mode = 4u;') == 1,
    'backend_colour_dispatch_mode11': backend.count('push.mode = 11u;') == 1,
    'amaze_guide_still_binding2': 'guideBarrier.buffer = rgbUpload_.buffer;' in backend,
    'amaze_modes_unchanged': 'push.mode = request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ? 6u : 8u;' in backend,
    'no_phase9_readback_added': 'POST_DEMOSAIC_RGB: outputRgb is authoritative' in shader,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('PHASE9_0065_RESIDUAL_DOMAIN_OWNERSHIP_FAIL=' + ','.join(failed))
print('PHASE9_0065_RESIDUAL_DOMAIN_OWNERSHIP_PASS=' + str(len(checks)))
