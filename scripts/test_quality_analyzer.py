"""Synthetic correctness checks; these are not camera-quality evidence."""
import json
import pathlib
import tempfile
import unittest
import numpy as np
from PIL import Image
import quality_analyzer as q


class Measurements(unittest.TestCase):
    def test_clipping_counts_channels_and_pixels(self):
        a=np.array([[[0,.5,1],[1,1,1],[.5,.5,.5]]])
        c=q.clipping(a)
        self.assertEqual(c['low']['singleChannel'],1)
        self.assertEqual(c['high']['singleChannel'],1)
        self.assertEqual(c['high']['allChannels'],1)

    def test_rejects_nonfinite_and_outside_roi(self):
        with self.assertRaises(ValueError):q.describe([np.nan])
        with self.assertRaises(ValueError):q.region(np.zeros((3,3,3)),[2,0,2,1])

    def test_edge_width_and_overshoot(self):
        p=np.r_[np.zeros(8),np.linspace(0,1,11),np.ones(8)]
        a=np.tile(p[None,:,None],(4,1,3))
        m=q.edge_profile(a,{})
        self.assertAlmostEqual(m['width10To90Pixels'],8)
        self.assertAlmostEqual(m['overshootFraction'],0)
        a[:,20,:]=1.2
        self.assertAlmostEqual(q.edge_profile(a,{})['overshootFraction'],.2)
        self.assertEqual(q.edge_profile(np.ones((4,24,3)),{})['status'],'UNMEASURABLE')

    def test_ambiguous_edge_does_not_report_ringing(self):
        p=np.r_[np.zeros(8),np.ones(8),np.zeros(8),np.ones(8)]
        result=q.edge_profile(np.tile(p[None,:,None],(4,1,3)),{})
        self.assertEqual(result['status'],'AMBIGUOUS')
        self.assertIsNone(result['width10To90Pixels'])
        self.assertIsNone(result['overshootFraction'])

    def test_same_raw_identity_and_nearest_crops(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);a=np.zeros((4,4,3),dtype=np.float32);a[1,1]=1
            np.save(root/'rgb.npy',a)
            entries=[dict(mode=mode,stage='FINAL_JPEG',file='rgb.npy',rawSha256='a'*64,
                          controlsSha256='b'*64,domain='ENCODED_SRGB') for mode in q.MODES]
            manifest=dict(rawSha256='a'*64,outputs=entries)
            path=root/'manifest.json';path.write_text(json.dumps(manifest))
            result=q.compare(path,root/'analysis')
            self.assertEqual(result['stages']['FINAL_JPEG']['differences']['Malvar__AMaZE']['max'],0)
            with Image.open(root/'analysis/FINAL_JPEG-position_center-Malvar-200-nearest.png') as im:
                pixels=np.asarray(im)
                self.assertEqual(im.size,(8,8))
                self.assertTrue((pixels[2:4,2:4]==255).all())
            entries[1]['rawSha256']='c'*64
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError,'Different RAW'):q.compare(path,root/'bad')
            entries[1]['rawSha256']='a'*64
            entries[1]['controlsSha256']='c'*64
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError,'Frozen controls'):q.compare(path,root/'bad-controls')

    def test_neutral_texture_has_zero_chroma_highpass(self):
        texture=np.arange(25,dtype=np.float32).reshape(5,5)/25
        a=np.repeat(texture[:,:,None],3,axis=2)
        measured=q.roi_metrics(a,'NEUTRAL_TEXTURE')
        self.assertEqual(measured['highFrequencyRedMinusGreen']['max'],0)
        self.assertEqual(measured['highFrequencyBlueMinusGreen']['min'],0)

    def test_raw_canonical_channels_for_each_cfa(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            for cfa,order in enumerate([[0,1,2,3],[1,0,3,2],[2,3,0,1],[3,2,1,0]]):
                a=np.empty((4,4),dtype='<u2')
                for pos,ch in enumerate(order):a[pos//2::2,pos%2::2]=[20,30,40,50][ch]
                a.tofile(root/'raw.bin')
                meta=dict(width=4,height=4,cfa=cfa,rawSha256=q.sha(root/'raw.bin'),whiteLevel=100,blackLevel='10,10,10,10')
                (root/'meta.json').write_text(json.dumps(meta))
                (root/'rois.json').write_text(json.dumps({'rois':[dict(id='odd',stage='RAW',kind='SYNTHETIC',box=[1,1,3,3])]}))
                result=q.raw_analysis(root/'raw.bin',root/'meta.json',root/'out',root/'rois.json')
                for ch,value in zip(['R','Gr','Gb','B'],[20,30,40,50]):
                    self.assertEqual(result['channels'][ch]['payload']['mean'],value)
                    self.assertAlmostEqual(result['channels'][ch]['normalized']['mean'],(value-10)/90)
                    self.assertEqual(result['rois']['odd']['channels'][ch]['payload']['mean'],value)

    def test_hybrid_oracle_detects_error(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d);a=np.zeros((2,3,20),dtype='<f4')
            a[:,:,0]=.25;a[:,:,1]=.75;a[:,:,8:11]=.2;a[:,:,11:14]=.6
            a[:,:,14:17]=.5;a[:,:,17]=1
            (root/'meta.json').write_text(json.dumps(dict(width=3,height=2)))
            a.tofile(root/'mask.bin')
            result=q.hybrid(root/'mask.bin',root/'meta.json',root/'out')
            self.assertLess(result['invariants']['oracleMax'],1e-7)
            a[0,0,14]+=.1;a.tofile(root/'mask.bin')
            self.assertGreater(q.hybrid(root/'mask.bin',root/'meta.json',root/'bad')['invariants']['oracleMax'],.09)


if __name__=='__main__':unittest.main()
