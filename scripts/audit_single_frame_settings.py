"""Static inventory plus reviewed priority traces. Device truth remains explicitly unresolved."""
import json
import pathlib
import re

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/single-frame-qualification'
sources={str(p.relative_to(ROOT)).replace('\\','/'):p.read_text(encoding='utf-8')
         for p in (ROOT/'app/src/main/java').rglob('*.kt')}
declarations=[]
for path,text in sources.items():
    for m in re.finditer(r'(\w+PreferencesKey)\(\s*"([^"\n]+)"',text):
        prefix=text[max(0,m.start()-300):m.start()]
        names=re.findall(r'(?:val|fun)\s+(\w+)',prefix)
        symbol=names[-1] if names else None
        hits=[]
        if symbol:
            for target,body in sources.items():
                if symbol in ['key','it','prefs','preferences'] and target != path:continue
                lines=[i for i,line in enumerate(body.splitlines(),1) if symbol in line and re.search(r'\b'+re.escape(symbol)+r'\b',line)]
                if lines:hits.append(dict(file=target,lines=lines))
        declarations.append(dict(key=m[2],type=m[1],symbol=symbol,file=path,
            line=text.count('\n',0,m.start())+1,references=hits,
            reviewStatus='INDEX_ONLY; dynamic keys and shared accessor calls need semantic tracing'))

rows=[]
def row(category,label,key,kind,default,range_,clamp,scope,session,affects,sources_,finding):
    rows.append(dict(category=category,uiLabel=label,storageKey=key,type=kind,
        uiDefault=default,schemaDefault=default,storedDeviceValue='UNAVAILABLE_ADB_DISCONNECTED',
        runtimeEffectiveValue='UNAVAILABLE_ADB_DISCONNECTED; obtain frozen recipe and result evidence',
        range=range_,clamp=clamp,scope=scope,restartOrSession=session,captureAffecting=affects,
        sourceAuthority=sources_,finding=finding,qualification='PARTIAL_STATIC_TRACE'))
B='app/src/main/java/com/bncam/'
row('demosaic','Demosaic','${profileId}_demosaic_mode','enum','Auto Hybrid',
    'Malvar|AMaZE|BnC Neural|Auto Hybrid','DemosaicMode.fromPersisted/resolveForPhase4','profile',False,True,
    [B+'core/quality/DemosaicMode.kt',B+'data/settings/CaptureSettingsSchema.kt',B+'core/capture/CaptureRecipeFactory.kt','app/src/main/cpp/Demosaic.cpp'],
    'Default agrees between capture schema and enum; bridge 2 migrates Menon to AMaZE. Neural slot falls back to Malvar and is excluded from this phase.')
row('RAW','Frame Source','profile_frame_source_${profileId}','enum','YUV','YUV|RAW10|RAW_SENSOR',
    'UI valid choices; runtime origin resolver','profile','session rebuild',True,
    [B+'data/settings/SettingsRepository.kt:getProfileFrameSourceFlow',B+'ui/screens/capture/CameraScreen.kt:activeProfileFrameSource',B+'core/engine/BnCameraManager.kt'],
    'Disabled profile is explicitly forced to YUV. Runtime session rebuild not device-qualified in this run.')
row('viewfinder','Viewfinder Stream','vf_stream','enum','YUV','YUV|SELECTED_BUFFER','ViewfinderStream.parse -> YUV fallback',
    'global','session/source transition',False,[B+'ui/screens/capture/ViewfinderStream.kt',B+'data/settings/SettingsRepository.kt:viewfinderStreamFlow',B+'core/engine/BnCameraManager.kt'],
    'Selected buffer follows active profile source. Preview-source choice and photo frame source are separate authorities.')
row('FPS','Derived Camera2 cadence','NO_INDEPENDENT_FPS_KEY_FOUND','derived','hardware-derived',
    'physical-compatible CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES','BnCameraManager physical-range selection',
    'camera session','Camera2 repeating request',True,[B+'core/engine/BnCameraManager.kt:AE_TARGET_FPS_RANGE_SELECTED'],
    'RAW sensor cadence owns range selection; exact effective range requires CaptureRequest/Result evidence.')
row('focus','Focus Mode','vf_focus_mode','string','Continuous','configured AF choices; fixed-focus depends on lens',
    'FocusOwnership and Camera2 capability resolution','global + physical lens','request update',True,
    [B+'data/settings/SettingsRepository.kt:focusModeFlow',B+'core/engine/FocusOwnership.kt',B+'core/engine/BnCameraManager.kt'],
    'UI default/capability behavior beyond Continuous still needs per-lens device verification.')
row('AE','Shot Bias Exposure','shot_bias_exposure (profile accessor)','enum','Auto','ShotBiasExposureChoice.uiValues',
    'CaptureExposurePreferences.sanitized + sensor allocator','profile','request update',True,
    [B+'core/capture/CaptureExposurePreferences.kt',B+'core/capture/CaptureRecipe.kt',B+'core/capture/DefaultRawExposureAllocator.kt'],
    'Historical shutter/ISO multiplier priority fields decode but sanitize to Balanced/1/1; they are legacy, not current authority.')
row('WB','Lens AWB','lens accessor: awb_profile/awb_ratio/awb_temp','string/float','System/Auto/0',
    'calibration/hardware constrained','resolved calibration; numeric range not fully audited','lens','request/runtime snapshot',True,
    [B+'data/settings/SettingsRepository.kt:getAwbProfileFlow',B+'core/quality/RenderQualityConfig.kt',B+'core/isp/raw/Raw16RenderInput.kt'],
    'Requested lens WB and frame-effective WB are distinct; evidence records effective gains. Full UI/range audit remains open.')
row('Spectra','Profile Neural RAW Denoise','spectra_profile_enabled','int','UNVERIFIED_UI_DEFAULT','0|1',
    'SettingsRepository clamp 0..1','profile',False,True,
    [B+'data/settings/ProfileLensTuningSettings.kt',B+'data/settings/SettingsRepository.kt',B+'core/quality/RenderQualityConfig.kt'],
    'Spectra persistence names now back Neural controls. No algorithm/tuning work done; old strength/dynamic keys are explicitly import-only.')
row('noise model','Physical Noise Model','lens-scoped hardware noise keys','S/O model','UNVERIFIED_UI_DEFAULT',
    'four CFA S/O channels; finite, >=0','LensNoiseModelSettings.sanitized','physical lens','snapshot/native hardware push',True,
    [B+'data/settings/ProfileLensTuningSettings.kt',B+'data/settings/PhysicalNoiseModelCaptureAuthority.kt',B+'data/settings/PhysicalNoiseModelRuntimeRegistry.kt'],
    'Physical lens model is separate from profile denoise. Stored device model unavailable; baseline provenance validator remains unresolved.')
row('tone mapping','Profile Tone','tone_* profile keys','float/curve','UNVERIFIED_UI_DEFAULT','per registry',
    'profile tone sanitizer','profile',False,True,[B+'core/quality/RenderQualityConfig.kt',B+'data/settings/ProfileLensTuningSettings.kt','app/src/main/cpp/IspCore.cpp'],
    'Profile tonal contrast is explicitly owned by ProfileToneTuning/GTM. Full control-by-control defaults still open.')
row('sharpening','Normal Sharpness','detail_sharpening_method/amount/edge/detail/legibility','enum/float',
    'method Normal; remaining UI defaults UNVERIFIED','signed authorities','ProfileSharpnessMethods.sanitize + profile ranges',
    'profile',False,True,[B+'core/quality/RenderQualityConfig.kt',B+'data/settings/ProfileLensTuningSettings.kt'],
    'Legacy radius is hidden. Independent signed owners documented; actual neutral RAW effective behavior requires native recipe/output trace.')
row('output','Output Policy','output_policy','enum','JPEG','JPEG|JPEG_PLUS_RAW|RAW_ONLY',
    'OutputPolicy.parse and source applicability','global capture policy',False,True,
    [B+'data/settings/CaptureSettingsSchema.kt',B+'core/capture/CaptureRouting.kt',B+'core/output/CapturePublishedOutputs.kt'],
    'Qualification JPEG checksum is the published file after EXIF. RAW checksum is canonical RAW16, not packed Camera2 RAW10 bytes.')
row('mirror','Mirror front preview','vf_mirror_front','boolean',True,'true|false','boolean',
    'global front lens',False,True,[B+'ui/screens/settings/ViewfinderScreen.kt',B+'core/runners/SingleFrameRunner.kt'],
    'Also mirrors saved photo; UI subtitle explicitly says Match preview with saved photo. Naming observation, not a proven bug.')
row('orientation','Physical orientation','sensor rotation; no replacement setting','sensor-derived','physical',
    'portrait/landscape left/right','getJpegOrientation','per capture',False,True,
    [B+'core/runners/SingleFrameRunner.kt:getJpegOrientation'],
    'Must qualify by physically rotating device. No software-rotation result is substituted.')
row('live tuning','Live Viewfinder Tuning','ViewfinderLiveTuning.snapshot (temporary)','runtime snapshot',
    'UNVERIFIED_UI_DEFAULT','snapshot-defined','applyColor/sanitized','live process + frozen recipe',False,True,
    [B+'core/quality/RenderQualityConfig.kt:liveViewfinderTuning.applyColor',B+'core/capture/CaptureRecipe.kt:liveViewfinderTuning'],
    'Proven to affect photo capture as well as preview because capture preferences apply the live snapshot. Product semantics/naming question only.')
OUT.mkdir(exist_ok=True,parents=True)
(OUT/'settings-audit.json').write_text(json.dumps(dict(declarations=declarations,priorityAudit=rows,
    completion='PARTIAL: static priority traces; device values and several UI/schema defaults require verification',
    provenProductBugs=[]),indent=2),encoding='utf-8')
print(f'{len(declarations)} persisted key call sites indexed; {len(rows)} priority traces; device truth unavailable')
