from pathlib import Path
import numpy as np

from spectra_train.reference_inference import read_golden_vector
from spectra_train.vulkan_reference import (
    compare_outputs,
    load_vulkan_reference_package,
    run_tiled_vulkan_package_reference,
    run_vulkan_package_reference,
)

ROOT=Path(__file__).resolve().parent
PACKAGE=ROOT/'golden'/'vulkan_package_contract'/'student_v1_contract.vkmodel'
GOLDEN=ROOT/'golden'/'student_v1_contract'/'golden_vector.zip'


def test_vulkan_package_unpacks_to_phase3_graph():
    package=load_vulkan_reference_package(PACKAGE)
    assert package.summary['source_model_content_sha256']
    assert package.summary['tensor_alignment_bytes']==16
    assert package.config.input_channels==14
    assert package.config.global_condition_dim==18


def test_package_reference_matches_phase3_golden_exactly():
    _, arrays=read_golden_vector(GOLDEN)
    got=run_vulkan_package_reference(PACKAGE, arrays['conditioning'], arrays['global_condition'])
    for name,value in got.items():
        expected=arrays[f'expected_{name}']
        assert np.array_equal(value, expected), name


def test_valid_center_tiling_matches_full_reference_exactly():
    _, arrays=read_golden_vector(GOLDEN)
    full=run_vulkan_package_reference(PACKAGE, arrays['conditioning'], arrays['global_condition'])
    tiled=run_tiled_vulkan_package_reference(PACKAGE, arrays['conditioning'], arrays['global_condition'])
    metrics=compare_outputs(full,tiled)
    for name in full:
        assert np.array_equal(tiled[name], full[name]), (name, metrics[name])


def test_phase3_clipped_cell_remains_identity():
    _, arrays=read_golden_vector(GOLDEN)
    got=run_vulkan_package_reference(PACKAGE, arrays['conditioning'], arrays['global_condition'])
    raw=arrays['raw_normalized'][None]
    assert np.array_equal(got['clean_raw'][...,0,0], raw[...,0,0])

def test_multiple_tiles_match_full_reference_exactly_including_internal_seams():
    # Larger than the 64px fixture tile in both axes, with dimensions that
    # exercise right/bottom replicate-to-8 padding as well as internal seams.
    rng=np.random.default_rng(4104)
    h,w=73,101
    raw=rng.uniform(0.02,0.92,size=(4,h,w)).astype(np.float32)
    shot=np.asarray([.0020,.0019,.0019,.0022],np.float32)[:,None,None]
    read=np.asarray([8e-5,7e-5,7e-5,9e-5],np.float32)[:,None,None]
    logs=np.log(np.maximum(np.sqrt(shot*raw+read),1e-8)).astype(np.float32)
    lsc=np.ones((4,h,w),np.float32)
    trust=np.full((1,h,w),.81,np.float32)
    head=np.min(np.clip((1-raw)/.08,0,1),axis=0,keepdims=True).astype(np.float32)
    cond=np.concatenate((raw,logs,lsc,trust,head),axis=0)
    glob=np.asarray([-.5,.2,0.,10/32,.9,.85,.84,.1,.1,.1,.05,.05,.1,.1,.02,.02,.01,.9],np.float32)
    full=run_vulkan_package_reference(PACKAGE,cond,glob)
    tiled=run_tiled_vulkan_package_reference(PACKAGE,cond,glob)
    for name in full:
        assert np.array_equal(tiled[name],full[name]), (name,compare_outputs(full,tiled)[name])
