[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RepoRoot,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$bncRepo = (Resolve-Path -LiteralPath $RepoRoot).Path
$bncPackage = $PSScriptRoot
$bncManifest = Get-Content -LiteralPath (Join-Path $bncPackage 'PATCH_MANIFEST.json') -Raw | ConvertFrom-Json
function Get-BncTarget {
    param([string]$Relative)
    $bncTarget = [System.IO.Path]::GetFullPath((Join-Path $bncRepo $Relative))
    $bncPrefix = $bncRepo.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $bncTarget.StartsWith($bncPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest path escapes repository: $Relative"
    }
    return $bncTarget
}
function Get-BncHash {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
# Check every input and target before the first repository mutation.
foreach ($bncGuard in $bncManifest.unchangedCore) {
    $bncPath = Get-BncTarget $bncGuard.path
    if (-not (Test-Path -LiteralPath $bncPath -PathType Leaf) -or (Get-BncHash $bncPath) -ne $bncGuard.sha256) {
        throw "Core source differs from reviewed upload: $($bncGuard.path). Merge/review locally; nothing applied."
    }
}
$bncPlan = @()
foreach ($bncFile in $bncManifest.files) {
    $bncSource = Join-Path (Join-Path $bncPackage 'payload') $bncFile.path
    if (-not (Test-Path -LiteralPath $bncSource -PathType Leaf) -or (Get-BncHash $bncSource) -ne $bncFile.sha256) {
        throw "Payload checksum failed: $($bncFile.path)"
    }
    $bncTarget = Get-BncTarget $bncFile.path
    $bncExists = Test-Path -LiteralPath $bncTarget
    if ($bncExists -and -not (Test-Path -LiteralPath $bncTarget -PathType Leaf)) {
        throw "Target is not a file: $($bncFile.path)"
    }
    if ($bncExists -and (Get-BncHash $bncTarget) -eq $bncFile.sha256) { continue }
    if ($bncFile.expectedCurrentSHA256) {
        if (-not $bncExists -or (Get-BncHash $bncTarget) -ne $bncFile.expectedCurrentSHA256) {
            throw "Existing source differs: $($bncFile.path). Merge/review locally; nothing applied."
        }
    } elseif ($bncExists) {
        throw "New target already exists with different contents: $($bncFile.path). Nothing applied."
    }
    $bncPlan += [pscustomobject]@{ File=$bncFile; Source=$bncSource; Target=$bncTarget; Existed=$bncExists }
}
if ($CheckOnly) {
    Write-Output "Checks passed; $($bncPlan.Count) files would change. No files written."
    return
}
if ($bncPlan.Count -eq 0) { Write-Output 'Package already applied; no files changed.'; return }
$bncStamp = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$bncBackup = Join-Path $bncRepo "build/bnc-neural-v2/v4-install-backups/$bncStamp"
New-Item -ItemType Directory -Path $bncBackup | Out-Null
Copy-Item -LiteralPath (Join-Path $bncPackage 'PATCH_MANIFEST.json') -Destination (Join-Path $bncBackup 'PATCH_MANIFEST.json')
$bncApplied = @()
$bncTemp = $null
try {
    foreach ($bncItem in $bncPlan) {
        if ($bncItem.Existed) {
            $bncOld = Join-Path $bncBackup $bncItem.File.path
            New-Item -ItemType Directory -Path (Split-Path -Parent $bncOld) -Force | Out-Null
            Copy-Item -LiteralPath $bncItem.Target -Destination $bncOld
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $bncItem.Target) -Force | Out-Null
        $bncTemp = $bncItem.Target + '.bncv4-' + [guid]::NewGuid().ToString('N') + '.partial'
        Copy-Item -LiteralPath $bncItem.Source -Destination $bncTemp
        Move-Item -LiteralPath $bncTemp -Destination $bncItem.Target -Force
        $bncTemp = $null
        $bncApplied += $bncItem
        if ((Get-BncHash $bncItem.Target) -ne $bncItem.File.sha256) { throw 'Installed checksum mismatch.' }
    }
} catch {
    $bncFailure = $_
    if ($bncTemp -and (Test-Path -LiteralPath $bncTemp)) { Remove-Item -LiteralPath $bncTemp }
    for ($bncIndex=$bncApplied.Count-1; $bncIndex -ge 0; $bncIndex--) {
        $bncItem = $bncApplied[$bncIndex]
        if ((Test-Path -LiteralPath $bncItem.Target) -and (Get-BncHash $bncItem.Target) -ne $bncItem.File.sha256) {
            Write-Warning "Concurrent change detected; rollback skipped for $($bncItem.File.path). Backup: $bncBackup"
            continue
        }
        if ($bncItem.Existed) {
            Copy-Item -LiteralPath (Join-Path $bncBackup $bncItem.File.path) -Destination $bncItem.Target -Force
        } elseif (Test-Path -LiteralPath $bncItem.Target) { Remove-Item -LiteralPath $bncItem.Target }
    }
    throw $bncFailure
}
Write-Output "Applied $($bncApplied.Count) files. Original files: $bncBackup"
Write-Output 'No checkpoints modified, no training started, no git commands executed.'
