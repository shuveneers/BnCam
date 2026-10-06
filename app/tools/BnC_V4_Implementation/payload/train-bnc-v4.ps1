[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('N','S')][string]$Candidate,
    [int]$StopAtStep = 0,
    [string]$OutputRoot,
    [switch]$PrepareOnly,
    [switch]$DiagnoseOnly,
    [switch]$ValidateOnly,
    [ValidateSet('cuda','cpu')][string]$Device = 'cuda'
)
$ErrorActionPreference = 'Stop'
if (([int]$PrepareOnly.IsPresent + [int]$DiagnoseOnly.IsPresent + [int]$ValidateOnly.IsPresent) -gt 1) {
    throw 'Choose exactly one read/prepare mode.'
}
if (-not $PrepareOnly -and -not $DiagnoseOnly -and -not $ValidateOnly -and $StopAtStep -le 0) {
    throw 'A positive explicit StopAtStep is required; first session <=2000.'
}
if (-not $PrepareOnly -and -not $DiagnoseOnly -and -not $ValidateOnly -and $Device -ne 'cuda') {
    throw 'Full corpus training requires CUDA; no CPU fallback.'
}
$bncRepo = $PSScriptRoot
$bncPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
if (-not (Test-Path -LiteralPath $bncPython)) { throw "Python runtime missing: $bncPython" }
$bncCuda = (Resolve-Path -LiteralPath (Join-Path $bncRepo 'build/bnc-neural-v2/python')).Path
$bncDeps = (Resolve-Path -LiteralPath (Join-Path $bncRepo 'build/bnc-neural/python')).Path
$bncOldPath = $env:PYTHONPATH
$bncOldCublas = $env:CUBLAS_WORKSPACE_CONFIG
$bncArguments = @('-u','-m','app.tools.bnc_neural_training.v2.v4_research.run',
                  '--candidate',$Candidate,'--device',$Device)
if ($PrepareOnly) { $bncArguments += '--prepare-only' }
elseif ($DiagnoseOnly) { $bncArguments += '--diagnose-only' }
elseif ($ValidateOnly) { $bncArguments += '--validate-only' }
else { $bncArguments += @('--stop-at-step',"$StopAtStep") }
if ($OutputRoot) {
    $bncRoot = if ([System.IO.Path]::IsPathRooted($OutputRoot)) {
        [System.IO.Path]::GetFullPath($OutputRoot)
    } else { [System.IO.Path]::GetFullPath((Join-Path $bncRepo $OutputRoot)) }
    $bncArguments += @('--output-root',$bncRoot)
}
try {
    $env:PYTHONPATH = "$bncRepo;$bncCuda;$bncDeps"
    $env:CUBLAS_WORKSPACE_CONFIG = ':4096:8'
    Push-Location -LiteralPath $bncRepo
    try {
        & $bncPython @bncArguments
        if ($LASTEXITCODE -ne 0) { throw "V4 stopped with exit $LASTEXITCODE; inspect status/failures. No restart attempted." }
    } finally { Pop-Location }
} finally {
    $env:PYTHONPATH = $bncOldPath
    $env:CUBLAS_WORKSPACE_CONFIG = $bncOldCublas
}
