"""Prepare immutable DNG-backed fixtures; no product settings or pixels are changed.
python app/tools/raw_truth/prepare_truth.py BUILD_DIRECTORY
"""
from pathlib import Path
import sys,json,re,struct,hashlib,shutil
from datetime import datetime
import numpy as np
import tifffile
from PIL import Image

def unwrap(s):
    lines=[]
    for line in s.splitlines():
        if line.startswith('.'*26) and lines: lines[-1]+=line[26:]
        else: lines.append(line)
    return '\n'.join(lines)

def numbers(s,key):
    m=re.search(re.escape(key)+r'[^\[\n]*\[([^]]+)\]',s)
    if not m: raise ValueError(key)
    return [float(x) for x in m[1].split(',')]

def gainmaps(blob):
    count,=struct.unpack_from('>I',blob);offset=4;maps={};details=[]
    for _ in range(count):
        op,version,flags,size=struct.unpack_from('>4I',blob,offset);offset+=16
        assert op==9
        vals=struct.unpack_from('>10I4dI',blob,offset)
        top,left,bottom,right,plane,planes,rp,cp,rows,cols,sv,sh,ov,oh,mp=vals
        assert (plane,planes,rp,cp,mp)==(0,1,2,2,1)
        maps[(top,left)]=np.frombuffer(blob,dtype='>f4',count=rows*cols,offset=offset+76).reshape(rows,cols).astype('f4')
        details.append(dict(zip(['top','left','bottom','right','plane','planes','rowPitch','colPitch','rows','cols','spacingV','spacingH','originV','originH','mapPlanes'],vals)))
        offset+=size
    assert offset==len(blob)
    return maps,details

def prepare(root):
    for name in ['golden','demosaic','stage-crops','lsc','metrics','dng','fixtures']: (root/name).mkdir(exist_ok=True)
    debug=root/'metadata';entries=[]
    for summary_path in debug.rglob('01_SUMMARY.txt'):
        d=summary_path.parent
        files={f.stem:unwrap(f.read_text(encoding='utf-8')) for f in d.glob('*.txt') if '.unwrapped' not in f.name}
        for n,s in files.items():(d/(n+'.unwrapped.txt')).write_text(s,encoding='utf-8')
        if '01_SUMMARY' not in files:continue
        summary=files['01_SUMMARY'];isp=files['04_ISP']
        entries.append((d,summary,isp,files))
    for f in sorted((root/'raw').glob('*.dng')):
        stamp=f.stem[8:23] # YYYYMMDD_HHMMSS
        matches=[v for v in entries if stamp in v[0].name]
        if not matches:
            moment=datetime.strptime(stamp,'%Y%m%d_%H%M%S')
            matches=[v for v in entries if abs((datetime.strptime(re.search(r'(\d{8}_\d{6})',v[0].name)[1],'%Y%m%d_%H%M%S')-moment).total_seconds())<=1]
        if not matches:continue
        if (root/'fixtures'/f.stem/'metadata.json').exists():continue
        d,summary,isp,files=matches[0]
        sensor=re.search(r'Camera ID\.+([^\n]+)',summary)[1]
        fmt=re.search(r'Frame source\.+([^\n]+)',summary)[1]
        iso=int(re.search(r'Capture ISO\.+(\d+)',isp)[1])
        folder=root/'fixtures'/f.stem;folder.mkdir(exist_ok=True)
        with tifffile.TiffFile(f) as tf:
            page=tf.pages[0]; tags=page.tags;raw=page.asarray();h,w=raw.shape
            pattern=list(tags[33422].value);assert pattern==[2,1,1,0],pattern
            black=numbers(isp,'Applied Black Levels');wb=numbers(isp,'WB Gains [R,G_even,G_odd,B]');ccm=numbers(isp,'Color Matrix Values')
            # Export prints five decimals. Keep those values, record the precision limit.
            white=float(tags[50717].value)
            # Applied Black Levels use [R,Gr,Gb,B], not Camera2's WB/LSC
            # [R,G_even,G_odd,B]. BGGR's even-row green is Gb.
            spatial_black=[black[3],black[2],black[1],black[0]]
            n=np.empty(raw.shape,dtype='f4')
            stats={}
            for index,ch in enumerate(['B','G_even','G_odd','R']):
                y,x=divmod(index,2);a=raw[y::2,x::2];b=spatial_black[index]
                n[y::2,x::2]=np.maximum(0,(a.astype('f4')-b)/(white-b))
                stats[ch]=dict(black=b,quantiles=np.percentile(a,[0,1,10,50,90,99,100]).tolist(),floor=int((a<=b).sum()),near_clip=int((a>=white-2).sum()),unique=int(len(np.unique(a))))
            maps,opcodes=gainmaps(tags[51009].value)
            gain=np.empty(raw.shape,dtype='f4')
            from scipy.ndimage import map_coordinates
            for (y,x),a in maps.items():
                yy,xx=np.meshgrid(np.arange(y,h,2)*(a.shape[0]-1)/(h-1),np.arange(x,w,2)*(a.shape[1]-1)/(w-1),indexing='ij')
                gain[y::2,x::2]=map_coordinates(a,[yy,xx],order=1,mode='nearest')
            np.save(folder/'gain.npy',gain);np.save(folder/'maps.npy',np.stack([maps[p] for p in [(1,1),(0,1),(1,0),(0,0)]],-1))
            raw.astype('<u2').tofile(folder/'raw.bin');n.tofile(folder/'normalized-off.f32');(n*gain).astype('f4').tofile(folder/'normalized-on.f32')
            (folder/'golden.txt').write_text(' '.join(map(str,[w,h,3,*wb,*ccm]))+'\n')
            tagdump={str(t.code)+' '+t.name: (t.value.hex() if isinstance(t.value,bytes) and len(t.value)<100 else str(t.value) if t.code!=51009 else 'GainMap decoded in opcodes') for t in tags.values()}
            info=dict(id=f.stem,sensor=sensor,format=fmt,iso=iso,width=w,height=h,cfa='BGGR',black_canonical=black,white=white,wb=wb,ccm=ccm,metadata_precision='WB/CCM from exact-frame debug export, rounded to 5 decimals; not invented extra precision',stats=stats,opcodes=opcodes,lsc_range={c:[float(a.min()),float(a.max())] for c,a in zip(['R','G_even','G_odd','B'],[maps[p] for p in [(1,1),(0,1),(1,0),(0,0)]])},dng_tags=tagdump,dng_sha256=hashlib.sha256(f.read_bytes()).hexdigest(),raw_sha256=hashlib.sha256(raw.astype('<u2').tobytes()).hexdigest(),debug_folder=str(d))
            (folder/'metadata.json').write_text(json.dumps(info,indent=2));(root/'metadata'/(f.stem+'.json')).write_text(json.dumps(info,indent=2))
            shutil.copy2(f,root/'dng'/f.name)
            # Full original debug retained; compact native replay metadata uses exact capture noise + DNG map.
            text=isp
            noise_match=re.search(r'noiseModelSoReceivedByCpp=\[([^]]+)\]',text)
            noise=[float(v) for v in noise_match[1].split(',')] if noise_match else [0]*8
            exposure_match=re.search(r'exposureTimeNs=(\d+)',text)
            if not exposure_match:raise ValueError('Missing actual exposure for '+f.stem)
            exposure=int(exposure_match[1])/1e6
            info['exposure_ns']=int(exposure_match[1]);(folder/'metadata.json').write_text(json.dumps(info,indent=2));(root/'metadata'/(f.stem+'.json')).write_text(json.dumps(info,indent=2))
            (folder/'fixture.txt').write_text(' '.join(map(str,[w,h,3,iso,int(fmt=='RAW_SENSOR'),exposure,white,*spatial_black,*wb,*ccm]))+'\n')
            m=np.stack([maps[p] for p in [(1,1),(0,1),(1,0),(0,0)]],-1)
            (folder/'capture-metadata.txt').write_text(' '.join(map(str,[1,*noise,m.shape[0],m.shape[1],*m.ravel()]))+'\n')
        im=Image.open(f.with_suffix('.jpg'));im.thumbnail((700,900));im.save(folder/'preview.jpg')
        print(f.stem,sensor,fmt,iso,w,h,flush=True)

if __name__=='__main__':prepare(Path(sys.argv[1]))
