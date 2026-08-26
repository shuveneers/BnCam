#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / 'src/main/cpp/Demosaic.cpp'
HDR = ROOT / 'src/main/cpp/Demosaic.h'
BACKEND_CPP = ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp'
BACKEND_HDR = ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h'
SHADER = ROOT / 'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp'
KOTLIN = ROOT / 'src/main/java/com/bncam/core/quality/DemosaicMode.kt'
RECIPE = ROOT / 'src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt'

checks = []
def require(name, condition):
    checks.append((name, bool(condition)))

def balanced(text: str, open_ch: str, close_ch: str) -> bool:
    # Conservative lexical balance check that ignores quoted strings and C/C++/GLSL comments.
    depth = 0
    quote = None
    escape = False
    line_comment = False
    block_comment = False
    i = 0
    while i < len(text):
        c = text[i]
        n = text[i+1] if i + 1 < len(text) else ''
        if line_comment:
            if c == '\n': line_comment = False
        elif block_comment:
            if c == '*' and n == '/':
                block_comment = False; i += 1
        elif quote:
            if escape: escape = False
            elif c == '\\': escape = True
            elif c == quote: quote = None
        else:
            if c == '/' and n == '/': line_comment = True; i += 1
            elif c == '/' and n == '*': block_comment = True; i += 1
            elif c == '"': quote = c
            elif c == open_ch: depth += 1
            elif c == close_ch:
                depth -= 1
                if depth < 0: return False
        i += 1
    return depth == 0 and quote is None and not block_comment

for p in (CPP, HDR, BACKEND_CPP, BACKEND_HDR, SHADER, KOTLIN, RECIPE):
    require(f'exists:{p.name}', p.exists())

cpp = CPP.read_text()
hdr = HDR.read_text()
bcpp = BACKEND_CPP.read_text()
bhdr = BACKEND_HDR.read_text()
shader = SHADER.read_text()
kt = KOTLIN.read_text()
recipe = RECIPE.read_text()

require('cpp-braces-balanced', balanced(cpp, '{', '}'))
require('backend-braces-balanced', balanced(bcpp, '{', '}'))
require('shader-braces-balanced', balanced(shader, '{', '}'))
require('kotlin-braces-balanced', balanced(kt, '{', '}'))

require('canonical-amaze-enum', 'Amaze = 4' in hdr and 'AmazeInspired = Amaze' in hdr)
require('cpu-three-plane-scratch', 'cv::Mat green;' in cpp and 'cv::Mat guide;' in cpp and 'cv::Mat rgb;' in cpp)
require('cpu-directional-guide', 'amazeAxisGuideAt(' in cpp and 'amazeAdaptiveOneSidedGreen(' in cpp)
require('cpu-nyquist', 'amazeNyquistPreScore(' in cpp and 'amazeSmoothedNyquist(' in cpp)
require('cpu-diagonal-chroma', 'amazeInterpolateDifferenceDiagonal(' in cpp)
require('cpu-zipper-clamp', 'amazeClampDifference(' in cpp)
require('cpu-validation-marker', 'BNCAM_AMAZE_CLEANROOM_MULTIPASS_V2' in cpp)
require('cpu-old-onepass-helper-removed', 'amazeGreenAtRedOrBlue(' not in cpp)

require('gpu-canonical-amaze-enum', 'AMAZE = 4u' in bhdr and 'AMAZE_INSPIRED = AMAZE' in bhdr)
require('gpu-pass-timings', 'amazeGreenPassMs' in bhdr and 'amazeReconstructPassMs' in bhdr)
require('gpu-guide-scratch-binding', 'scratchOverride' in bcpp and 'rgbUpload_.buffer' in bcpp)
require('gpu-guide-barrier', 'VkBufferMemoryBarrier guideBarrier' in bcpp and 'VK_ACCESS_SHADER_WRITE_BIT' in bcpp)
require('gpu-mode5-guide', 'push.mode = 5u' in bcpp)
require('gpu-mode6-reconstruct', 'push.mode = 6u' in bcpp)
require('gpu-awb-restores-binding2', 'updateDescriptorSetLocked(device);' in bcpp)

require('shader-binding2-readable', 'layout(std430, binding = 2) buffer ColorRgb' in shader)
require('shader-guide-pass', 'void amazeGuidePass(' in shader)
require('shader-reconstruct-pass', 'vec3 amazeReconstructAt(' in shader)
require('shader-nyquist', 'float amazeNyquistPreScore(' in shader and 'float amazeSmoothedNyquist(' in shader)
require('shader-diagonal-chroma', 'float amazeDiagonalDifference(' in shader)
require('shader-old-onepass-amaze-removed', 'vec3 amazeAt(' not in shader)
require('shader-mode5-branch', 'if (pc.mode == 5u)' in shader)
require('shader-mode6-branch', 'if (pc.mode == 6u)' in shader)

require('ui-label-amaze', 'QUALITY(2, "AMaZE", true)' in kt)
require('legacy-amaze-profile-migration', '"AMAZE_INSPIRED"' in kt)
require('neural-debug-name', 'ResolvedDemosaicAlgorithm.RCD_INSPIRED -> "NEURAL_JDD"' in kt)
require('amaze-debug-name', 'ResolvedDemosaicAlgorithm.AMAZE_INSPIRED -> "AMAZE"' in kt)
require('profile-telemetry-canonical', 'resolvedId = demosaicSelection.resolvedDebugName' in recipe)
require('no-product-amaze-inspired-label', 'QUALITY(2, "AMaZE Inspired"' not in kt)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(('PASS ' if ok else 'FAIL ') + name)
if failed:
    print(f'FAILED {len(failed)} contract checks', file=sys.stderr)
    sys.exit(1)
print(f'PASS all {len(checks)} AMaZE multipass contract checks')
