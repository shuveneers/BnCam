import hashlib
import json
import unittest
import numpy as np
import torch
from torch.utils.data import DataLoader,Dataset
from ..procedural import (KINDS,LUMA,MIN_ISOLUMINANT_CHROMATIC_DISTANCE,
    isoluminant_pair,rng_for,scene)


class ProceduralWorkers(Dataset):
    def __len__(self):return 312
    def __getitem__(self,index):
        rgb,meta=scene(index+30000,'train',size=24,seed=823109)
        return torch.from_numpy(rgb),KINDS.index(meta['kind'])


class ProceduralReliabilityTests(unittest.TestCase):
    def test_same_procedural_samples_with_zero_and_multiple_workers(self):
        source=ProceduralWorkers()
        single=iter(DataLoader(source,batch_size=24,num_workers=0))
        parallel=iter(DataLoader(source,batch_size=24,num_workers=3,prefetch_factor=2))
        seen=set()
        for (one,kind1),(many,kind3) in zip(single,parallel):
            self.assertTrue(torch.equal(one,many))
            self.assertTrue(torch.equal(kind1,kind3))
            seen.update(kind1.tolist())
        self.assertIn(KINDS.index('isoluminant_edge'),seen)
        self.assertIn(KINDS.index('mixed_color_edge'),seen)
    def test_100k_constructive_palettes_without_rejection_or_gamut_failure(self):
        luma=np.array(LUMA,dtype=np.float64)
        lefts=[];rights=[]
        for index in range(100000):
            split=('train','validation','test')[index%3]
            left,right=isoluminant_pair(rng_for(split,index,823109))
            self.assertTrue(np.isfinite(left).all() and np.isfinite(right).all())
            self.assertGreaterEqual(min(left.min(),right.min()),0.)
            self.assertLessEqual(max(left.max(),right.max()),1.)
            self.assertLessEqual(abs(float(left@luma-right@luma)),1e-7)
            self.assertGreaterEqual(float(np.linalg.norm(left-right)),MIN_ISOLUMINANT_CHROMATIC_DISTANCE)
            if index<1000:lefts.append(left);rights.append(right)
        self.assertGreater(float(np.std(lefts)),.1)
        self.assertGreater(float(np.std(rights)),.1)

    def test_thousands_of_full_scenes_and_worker_independent_seed_index(self):
        luma=np.array(LUMA,dtype=np.float64)
        neutral_count=0
        for index in range(6000):
            split=('train','validation','test')[index%3]
            kind='isoluminant_edge' if index%3==0 else 'mixed_color_edge' if index%3==1 else 'grating'
            rgb,meta=scene(index,split,size=32,seed=823109,_kind_for_test=kind)
            self.assertTrue(np.isfinite(rgb).all())
            self.assertGreaterEqual(float(rgb.min()),0.)
            self.assertLessEqual(float(rgb.max()),4.)
            if kind=='isoluminant_edge':
                left=np.array(meta['leftColor']);right=np.array(meta['rightColor'])
                self.assertLessEqual(abs(float(left@luma-right@luma)),1e-7)
                self.assertGreaterEqual(meta['chromaticDistance'],MIN_ISOLUMINANT_CHROMATIC_DISTANCE)
                self.assertFalse(np.allclose(left,right))
                if index<150:np.testing.assert_array_equal(rgb,scene(index,split,size=32,seed=823109,_kind_for_test=kind)[0])
            elif kind=='grating' and meta['neutral']:
                neutral_count+=1
                self.assertEqual(float(np.max(abs(rgb[...,0]-rgb[...,1]))),0.)
                self.assertEqual(float(np.max(abs(rgb[...,2]-rgb[...,1]))),0.)
        for index in range(6000,12000):
            rgb,meta=scene(index,'train',size=32,seed=823109,_kind_for_test='grating')
            if meta['neutral']:
                neutral_count+=1
                self.assertEqual(float(np.max(abs(rgb[...,0]-rgb[...,1]))),0.)
                self.assertEqual(float(np.max(abs(rgb[...,2]-rgb[...,1]))),0.)
        self.assertGreaterEqual(neutral_count,2000)

    def test_non_isoluminant_training_scenes_match_pre_fix_digest(self):
        sha=hashlib.sha256();compared=0
        for index in range(1000):
            rgb,meta=scene(index,'train',size=24,seed=823109)
            if meta['kind']=='isoluminant_edge':continue
            sha.update(index.to_bytes(4,'little'));sha.update(rgb.tobytes());sha.update(json.dumps(meta,sort_keys=True).encode())
            compared+=1
        self.assertEqual(compared,917)
        self.assertEqual(sha.hexdigest(),'a74c10c7e27693e6f1b9ece67cf2ed22295412a7ce00792c75a50f8a9ca2770a')


if __name__=='__main__':unittest.main()
