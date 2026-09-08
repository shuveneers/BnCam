from __future__ import annotations
import json, subprocess
from pathlib import Path
from spectra_train.generate_vulkan_parity_contract import build_parity_contract

ROOT=Path(__file__).resolve().parent
PACKAGE=ROOT/'golden'/'vulkan_package_contract'/'student_v1_contract.vkmodel'
GOLDEN=ROOT/'golden'/'student_v1_contract'/'golden_vector.zip'
PARITY=ROOT/'golden'/'vulkan_parity_contract'/'phase4_parity.json'
CPP=ROOT.parents[2]/'app'/'src'/'main'/'cpp'


def test_frozen_phase4_parity_contract_is_current_and_exact():
    expected=json.loads(PARITY.read_text())
    actual=build_parity_contract(PACKAGE,GOLDEN)
    assert actual==expected
    assert actual['phase3_package_reference_exact']
    assert actual['valid_center_tiled_reference_exact']
    assert actual['clipped_reference_cell_identity']
    assert actual['gpu_validation_gate']['status']=='REQUIRED_ON_ANDROID_VULKAN_DEVICE'


def test_neural_cmake_module_attaches_sources_without_creating_runtime(tmp_path: Path):
    module=(CPP/'vulkan'/'cmake'/'SpectraNeuralBackend.cmake').as_posix()
    (tmp_path/'dummy.cpp').write_text('int neural_phase4_cmake_dummy(){return 0;}\n')
    (tmp_path/'CMakeLists.txt').write_text(f'''cmake_minimum_required(VERSION 3.22)\nproject(neural_attach LANGUAGES CXX)\nset(BNCAM_GLSLC "")\nset(BNCAM_GENERATED_SHADER_DIR "${{CMAKE_CURRENT_BINARY_DIR}}/generated")\nadd_library(mock STATIC dummy.cpp)\ninclude("{module}")\nbncam_attach_spectra_neural_backend(mock)\nget_target_property(_defs mock COMPILE_DEFINITIONS)\nif(NOT "BNCAM_NEURAL_SHADERS_AVAILABLE=0" IN_LIST _defs)\n  message(FATAL_ERROR "neural availability compile definition missing")\nendif()\n''')
    result=subprocess.run(['cmake','-S',str(tmp_path),'-B',str(tmp_path/'build')],capture_output=True,text=True)
    assert result.returncode==0, result.stdout+'\n'+result.stderr
