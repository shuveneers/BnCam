param(
    [Parameter(Mandatory=$true)][ValidateSet('A','B','D')][string]$Scene,
    [string]$Serial = 'AUWE025B03006422',
    [string]$Python,
    [string]$Output,
    [string]$Rois
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Python) {
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython) { $Python = $bundledPython }
    else { $Python = (Get-Command python -ErrorAction Stop).Source }
}
& $Python -c 'import numpy, PIL'
if ($LASTEXITCODE -ne 0) { throw 'Python requires numpy and Pillow. Pass -Python with a suitable runtime.' }
Push-Location $repoRoot
try {
    $runnerArgs = @('scripts/raw10_baseline.py', '--scene', $Scene, '--serial', $Serial)
    if ($Output) { $runnerArgs += @('--output', $Output) }
    if ($Rois) { $runnerArgs += @('--rois', $Rois) }
    & $Python @runnerArgs
    if ($LASTEXITCODE -ne 0) { throw 'RAW10 baseline stopped; inspect preserved evidence. No extra frames will be captured.' }
} finally { Pop-Location }
