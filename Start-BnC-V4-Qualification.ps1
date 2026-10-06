[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$bncRepo = $PSScriptRoot
$bncPython = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
if (-not (Test-Path -LiteralPath $bncPython)) { throw "Python runtime missing: $bncPython" }
$bncCuda = (Resolve-Path -LiteralPath (Join-Path $bncRepo 'build/bnc-neural-v2/python')).Path
$bncDeps = (Resolve-Path -LiteralPath (Join-Path $bncRepo 'build/bnc-neural/python')).Path
$bncOldPath = $env:PYTHONPATH
$bncOldCublas = $env:CUBLAS_WORKSPACE_CONFIG
$bncStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$bncEvidence = Join-Path $bncRepo "build/bnc-neural-v2/v4-verification/local-$bncStamp"
$bncResearch = Join-Path $bncRepo 'build/bnc-neural-v2/v4-research'
$bncArchive = Join-Path $bncRepo "build/bnc-neural-v2/v4-verification/V4-qualification-$bncStamp.zip"
$bncSummary = [ordered]@{ productReady=$false; stopAtStep=2000; automaticContinuation=$false; candidates=@() }
New-Item -ItemType Directory -Path $bncEvidence | Out-Null
function Invoke-BncCheckedPython {
    param([string[]]$Arguments, [string]$Log)
    $bncSavedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 turns redirected unittest stderr into error records.
        $ErrorActionPreference = 'Continue'
        & $bncPython @Arguments 2>&1 | Tee-Object -FilePath $Log
        $bncExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $bncSavedPreference }
    if ($bncExit -ne 0) { throw "Python exited with $bncExit; inspect $Log" }
}
try {
    $env:PYTHONPATH = "$bncRepo;$bncCuda;$bncDeps"
    $env:CUBLAS_WORKSPACE_CONFIG = ':4096:8'
    Push-Location -LiteralPath $bncRepo
    try {
        $bncTests = @('-m','unittest',
            'app.tools.bnc_neural_training.tests.test_contracts',
            'app.tools.bnc_neural_training.v2.v3_phase_a.test_model',
            'app.tools.bnc_neural_training.v2.v4_research.test_research','-v')
        Invoke-BncCheckedPython -Arguments $bncTests -Log (Join-Path $bncEvidence 'unit-tests.log')
        $bncReplay = @('-m','app.tools.bnc_neural_training.v2.v4_research.verify_local',
            '--device','cuda','--out',(Join-Path $bncEvidence 'real-checkpoint-replay.json'))
        Invoke-BncCheckedPython -Arguments $bncReplay -Log (Join-Path $bncEvidence 'real-checkpoint-replay.log')
        foreach ($bncCandidate in @('N','S')) {
            $bncCandidateRoot = Join-Path $bncResearch $bncCandidate
            try {
                $bncStatusPath = Join-Path $bncCandidateRoot 'status.json'
                $bncPrior = if (Test-Path -LiteralPath $bncStatusPath) {
                    Get-Content -LiteralPath $bncStatusPath -Raw | ConvertFrom-Json
                } else { $null }
                if ($bncPrior -and $bncPrior.status -eq 'RESEARCH_STOP') {
                    throw 'This candidate has a latched research stop. No restart attempted.'
                }
                if ($bncPrior -and $bncPrior.step -gt 2000) {
                    throw 'This candidate has passed the first qualification stage. No backward/extra training attempted.'
                }
                & (Join-Path $bncRepo 'train-bnc-v4.ps1') -Candidate $bncCandidate -PrepareOnly
                & (Join-Path $bncRepo 'train-bnc-v4.ps1') -Candidate $bncCandidate -DiagnoseOnly
                if ($bncPrior -and $bncPrior.status -eq 'SESSION_STOPPED' -and $bncPrior.step -eq 2000) {
                    & (Join-Path $bncRepo 'train-bnc-v4.ps1') -Candidate $bncCandidate -ValidateOnly
                } else {
                    & (Join-Path $bncRepo 'train-bnc-v4.ps1') -Candidate $bncCandidate -StopAtStep 2000
                }
                $bncSummary.candidates += [ordered]@{ candidate=$bncCandidate; status='SESSION_COMPLETED'; error=$null }
            } catch {
                $bncSummary.candidates += [ordered]@{ candidate=$bncCandidate; status='STOPPED'; error=$_.Exception.Message }
                Write-Warning "Candidate $bncCandidate stopped: $($_.Exception.Message). No retry."
            }
        }
        $bncNIdentity = Join-Path $bncResearch 'N/identity.json'
        $bncSIdentity = Join-Path $bncResearch 'S/identity.json'
        if ((Test-Path -LiteralPath $bncNIdentity) -and (Test-Path -LiteralPath $bncSIdentity)) {
            $bncN = Get-Content -LiteralPath $bncNIdentity -Raw | ConvertFrom-Json
            $bncS = Get-Content -LiteralPath $bncSIdentity -Raw | ConvertFrom-Json
            $bncSummary['matchedInitialWeights'] = $bncN.initialWeightsSHA256 -eq $bncS.initialWeightsSHA256
            if (-not $bncSummary.matchedInitialWeights) { throw 'N/S initial weights differ; comparison is invalid.' }
        }
    } finally { Pop-Location }
} catch {
    $bncSummary['controllerError'] = $_.Exception.Message
    throw
} finally {
    $env:PYTHONPATH = $bncOldPath
    $env:CUBLAS_WORKSPACE_CONFIG = $bncOldCublas
    $bncSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $bncEvidence 'qualification-summary.json') -Encoding UTF8
    $bncPaths = @($bncEvidence)
    foreach ($bncCandidate in @('N','S')) {
        $bncPath = Join-Path $bncResearch $bncCandidate
        if (Test-Path -LiteralPath $bncPath) { $bncPaths += $bncPath }
    }
    Compress-Archive -LiteralPath $bncPaths -DestinationPath $bncArchive -CompressionLevel Optimal
    Write-Output "Evidence package: $bncArchive"
}
