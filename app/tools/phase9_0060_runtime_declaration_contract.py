from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
h = (root / 'src/main/cpp/vulkan/VulkanRuntime.h').read_text(encoding='utf-8')

pattern = re.compile(
    r'SpectraResidentPreDemosaicResult\s+executeSpectraResidentPreDemosaicPass1FromRawNormalize\s*\(\s*'
    r'const\s+SpectraResidentPreDemosaicRequest&\s+request\s*,\s*'
    r'std::uint64_t\s+rawNormalizeGeneration\s*\)\s*noexcept\s*;'
)
checks = {
    'declaration_present': bool(pattern.search(h)),
    'declaration_unique': h.count('executeSpectraResidentPreDemosaicPass1FromRawNormalize') == 1,
    'pass0_handoff_preserved': 'executeSpectraResidentPreDemosaicPass0FromRawNormalize' in h,
    'ordinary_pass1_preserved': 'executeSpectraResidentPreDemosaicPass1(' in h,
    'phase9_scene_observer_contract_preserved': 'Phase 9: consume upstream-protected post-CCM RGB' in h,
}
failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE9_0060_RUNTIME_DECLARATION_CONTRACT_FAIL=' + ','.join(failed))
print(f'PHASE9_0060_RUNTIME_DECLARATION_CONTRACT_PASS={len(checks)}')
