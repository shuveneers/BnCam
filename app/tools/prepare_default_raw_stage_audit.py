"""Make an instrumented COPY of the current native sources for RAW stage measurement.
The production tree/APK is not instrumented. Dumps are opt-in via BNCAM_RAW_AUDIT_DIR.
Requires the ordinary Android SDK/NDK and the checked-in OpenCV SDK.
"""
import json
import shutil
import argparse
from pathlib import Path
root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(__doc__)
parser.add_argument('--source', type=Path, default=root/'app/src/main/cpp')
parser.add_argument('--build', type=Path, default=root/'build/default-raw-stage-audit')
args = parser.parse_args()
build = args.build.resolve()
source = build / 'src'
source.mkdir(parents=True, exist_ok=True)
shutil.copytree(args.source, source, dirs_exist_ok=True)
rois = {'blue_hood': [1230,1900,160,80], 'dark_glass':[2780,1278,64,24],
        'foliage':[1000,350,160,128], 'white_van':[1840,1360,64,24],
        'neutral_wall':[1400,1190,128,96], 'sky':[1275,1000,48,24],
        'near_clip':[2336,1856,64,64]}
(build/'rois.json').write_text(json.dumps(rois,indent=2),encoding='utf-8')
header = r'''
#pragma once
#include <fstream>
#include <cstdlib>
#include <string>
#include <algorithm>
#include <cmath>
inline int rawAuditStage(){const char* s=std::getenv("BNCAM_RAW_AUDIT_STAGE");return s?std::atoi(s):0;}
inline bool rawAuditEnabled(){return std::getenv("BNCAM_RAW_AUDIT_DIR")!=nullptr;}
inline void rawAuditDump(const char* stage,const float* rgb,int w,int h){
 const char* directory=std::getenv("BNCAM_RAW_AUDIT_DIR");if(!directory||!rgb)return;
 std::uint64_t negative=0,nonfinite=0;float minimum=0;
 for(std::size_t i=0;i<std::size_t(w)*h;++i){const float*r=rgb+i*3;
  if(!std::isfinite(r[0])||!std::isfinite(r[1])||!std::isfinite(r[2]))++nonfinite;
  if(std::min({r[0],r[1],r[2]}) < -1.e-6f)++negative;
  minimum=std::min(minimum,std::min({r[0],r[1],r[2]}));
 }
 std::ofstream(std::string(directory)+"/"+stage+"-counts.txt")<<std::uint64_t(w)*h<<" "<<negative<<" "<<nonfinite<<" "<<minimum<<"\n";
 struct Patch {const char* name;int x,y,w,h;};
 const Patch patches[]={PATCHES};
 for(const auto&p:patches){std::ofstream out(std::string(directory)+"/"+stage+"__"+p.name+".f32",std::ios::binary);
  for(int y=p.y;y<p.y+p.h;++y)for(int x=p.x;x<p.x+p.w;++x){
   // Clockwise 90-degree publication orientation; source buffers stay unrotated.
   int sx=y,sy=h-1-x;if(sx<0||sx>=w||sy<0||sy>=h)std::abort();
   out.write(reinterpret_cast<const char*>(rgb+(sy*w+sx)*3),12);
  }
 }
}
inline void rawAuditTransform(const float* wb,const float* matrix){
 const char*d=std::getenv("BNCAM_RAW_AUDIT_DIR");if(!d)return;
 std::ofstream out(std::string(d)+"/transform.f32",std::ios::binary);
 out.write(reinterpret_cast<const char*>(wb),12);out.write(reinterpret_cast<const char*>(matrix),36);
}
'''
header=header.replace('PATCHES',','.join('{"'+k+'",'+','.join(map(str,v))+'}' for k,v in rois.items()))
(source/'RawStageAuditOnly.h').write_text(header,encoding='utf-8')
def edit(path,old,new):
 p=source/path;s=p.read_text();assert old in s,(path,old[:100]);p.write_text(s.replace(old,new,1))
edit(Path('IspCore.cpp'),'#include "HighlightGamutProtectionV2.h"','#include "HighlightGamutProtectionV2.h"\n#include "RawStageAuditOnly.h"')
edit(Path('IspCore.cpp'),'        request.neutralDefaultRaw = neutralDefaultRaw;\n        request.rgbData',
     '        request.neutralDefaultRaw = neutralDefaultRaw;\n        request.physicalChromaValidationOnly = rawAuditStage() < 0;\n        request.rgbData')
# Both color/tone return floats in the instrumented copy only.
p=source/'IspCore.cpp';s=p.read_text().replace('request.deferFullReadback = true;', 'request.deferFullReadback = !rawAuditEnabled();').replace('request.bgr8PublicationRequested = true;', 'request.bgr8PublicationRequested = !rawAuditEnabled();');p.write_text(s)
edit(Path('IspCore.cpp'),'        vulkanColorTransform =\n', '        rawAuditTransform(wbRgb, ccm);\n        vulkanColorTransform =\n')
edit(Path('IspCore.cpp'),'    const size_t expectedColorPixels =',
     '    if (!vulkanColorTransform.outputRgb.empty()) rawAuditDump(rawAuditStage()==-1 ? "post_demosaic" : rawAuditStage()==-2 ? "post_existing_denoise" : rawAuditStage()==-3 ? "post_wb" : rawAuditStage()==-4 ? "post_ccm" : "post_highlight_characterization", vulkanColorTransform.outputRgb.data(), demosaicInputWidth, demosaicInputHeight);\n    const size_t expectedColorPixels =')
edit(Path('vulkan/VulkanSpectraResidentDemosaicBackend.cpp'),'#include "VulkanSpectraResidentDemosaicBackend.h"','#include "VulkanSpectraResidentDemosaicBackend.h"\n#include "../RawStageAuditOnly.h"')
edit(Path('vulkan/VulkanSpectraResidentDemosaicBackend.cpp'),
     '    push.cfaEvidence1[2] = request.physicalChromaValidationOnly ? 1.f : 0.f;',
     '    push.cfaEvidence1[2] = rawAuditStage()==-4 ? 4.f : rawAuditStage()==-3 ? 3.f : rawAuditStage()==-1 ? 2.f : request.physicalChromaValidationOnly ? 1.f : 0.f;')
# A diagnostic readback must not revoke the resident color generation: doing so
# would silently skip GPU FLLF/tone. Publish both readback and resident ownership.
edit(Path('vulkan/VulkanSpectraResidentDemosaicBackend.cpp'),
     '    if (result.success && request.deferFullReadback) {',
     '    if (result.success && (request.deferFullReadback || rawAuditEnabled())) {')
edit(Path('vulkan/VulkanSpectraResidentDemosaicBackend.cpp'),
     '            : VK_ACCESS_TRANSFER_READ_BIT;',
     '            : VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;')
edit(Path('vulkan/VulkanSpectraResidentDemosaicBackend.cpp'),
     '            : VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT;',
     '            : VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;')
edit(Path('vulkan/shaders/spectra_demosaic_resident.comp'),
     'if(pc.cfaEvidence1.z>0.5) protectedCcm=rawForWb;',
     'if(pc.cfaEvidence1.z>0.5) protectedCcm=pc.cfaEvidence1.z>3.5 ? signedCcm : pc.cfaEvidence1.z>2.5 ? legacyWb : pc.cfaEvidence1.z>1.5 ? rawInput : rawForWb;')
edit(Path('IspCore.cpp'),'        if (vulkanTone.ultraHdrGainmapGenerated',
     '        if (!vulkanTone.outputRgb.empty()) rawAuditDump("tone", vulkanTone.outputRgb.data(), demosaicInputWidth, demosaicInputHeight);\n        if (vulkanTone.ultraHdrGainmapGenerated')
edit(Path('vulkan/VulkanSpectraResidentToneBackend.cpp'),'#include "VulkanSpectraResidentToneBackend.h"','#include "VulkanSpectraResidentToneBackend.h"\n#include "../RawStageAuditOnly.h"')
edit(Path('vulkan/VulkanSpectraResidentToneBackend.cpp'),'    push.mode = 3u;',
     '    push.mode = 3u;\n    push.presenceReserved1 = float(rawAuditStage());')
# Only diagnostic stop points; arithmetic before each stop is the actual production shader.
edit(Path('vulkan/shaders/spectra_tone_resident.comp'),'        vec3 preKhronos = rgb;',
     '        if (pc.presenceReserved1 == 1.0) { writeWorking(gid, rgb); return; }\n        vec3 preKhronos = rgb;')
edit(Path('vulkan/shaders/spectra_tone_resident.comp'),'        rgb = applyToneLookLut(rgb);',
     '        if (pc.presenceReserved1 == 2.0) { writeWorking(gid, rgb); return; }\n        rgb = applyToneLookLut(rgb);')
edit(Path('vulkan/shaders/spectra_tone_resident.comp'),'    return compressToUnitGamutPreserveLuma(rgb);\n}',
     '    if (pc.presenceReserved1 == 4.0) return rgb;\n    return compressToUnitGamutPreserveLuma(rgb);\n}')
print(source)
