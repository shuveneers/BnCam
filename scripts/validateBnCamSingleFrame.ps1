param(
    [string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Fixture = '/data/local/tmp/bncam-validation/main',
    [switch]$LocalOnly,
    [int]$MeasuredRuns = 10
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr')) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}
$evidenceRoot = Join-Path $repoRoot 'work\single-frame-gate'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null
function Invoke-Checked([string]$Program, [string[]]$Arguments, [string]$Log) {
    & $Program @Arguments *> $Log
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $Log -Tail 40
        throw "Gate failed ($LASTEXITCODE): $Program $($Arguments -join ' '); see $Log"
    }
}
$tests = @('CaptureTerminalAccountingTest','CaptureAttemptCoordinatorTest','CaptureProcessingQueueTest',
    'CaptureProcessingLifecycleTest','Phase1ASingleYuvLifecycleContractTest',
    'PostPhase10HardwareShutterInputSourceContractTest','FocusOwnershipStateTest','FrameSelectionExposurePolicyTest','DemosaicModeTest')
$gradleArgs = @('assembleDebug','assembleDebugAndroidTest',':app:testDebugUnitTest','--console=plain')
foreach ($test in $tests) { $gradleArgs += @('--tests',"*$test") }
Invoke-Checked '.\gradlew.bat' $gradleArgs (Join-Path $evidenceRoot 'local.log')
Invoke-Checked 'git' @('diff','--check') (Join-Path $evidenceRoot 'diff-check.txt')
if ($LocalOnly) {
    Write-Output 'LOCAL CHECKS PASSED. Device/native/replay/camera/benchmark gates NOT RUN.'
    return
}
if (-not $Serial) {
    $deviceLines = & $Adb devices
    $available = @($deviceLines | Where-Object { $_ -match '^\S+\s+device$' })
    if ($available.Count -ne 1) { throw 'One authorized ADB device is required; use -Serial when several are connected.' }
    $Serial = ($available[0] -split '\s+')[0]
}
$env:ANDROID_SERIAL = $Serial
$classes = @('DemosaicNativeValidationTest','NoiseModelNativeConnectedTest','VulkanDeviceVerificationTest','PhysicalChromaDeviceTest','PhysicalLumaDeviceTest',
    'PublicationPolicyDeviceTest','RawPreviewRecoveryDeviceTest','CaptureTerminalBackpressureDeviceTest') |
    ForEach-Object { "com.bncam.$_" }
Invoke-Checked '.\gradlew.bat' @(':app:connectedDebugAndroidTest',
    '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
    "-Pandroid.testInstrumentationRunnerArguments.class=$($classes -join ',')",'--console=plain') (Join-Path $evidenceRoot 'connected.log')
$nativeExecutables = @(Get-ChildItem -LiteralPath 'app\build\intermediates\cxx\Debug' -Recurse -Filter 'bncam_single_frame_qualification' |
    Sort-Object LastWriteTime -Descending)
if ($nativeExecutables.Count -eq 0) { throw 'Native qualification executable missing.' }
$nativeDir = $nativeExecutables[0].DirectoryName
$deviceDir = '/data/local/tmp/bncam-validation'
Invoke-Checked $Adb @('-s',$Serial,'shell','mkdir','-p',$deviceDir) (Join-Path $evidenceRoot 'mkdir.log')
foreach ($name in @('bncam_single_frame_qualification','libbncam.so')) {
    Invoke-Checked $Adb @('-s',$Serial,'push',(Join-Path $nativeDir $name),"$deviceDir/") (Join-Path $evidenceRoot "push-$name.log")
}
Invoke-Checked $Adb @('-s',$Serial,'shell','chmod','755',"$deviceDir/bncam_single_frame_qualification") (Join-Path $evidenceRoot 'chmod.log')
Invoke-Checked $Adb @('-s',$Serial,'push','opencv\native\libs\arm64-v8a\libopencv_java4.so',"$deviceDir/") (Join-Path $evidenceRoot 'push-opencv.log')
$cppLibrary = Get-ChildItem -LiteralPath 'app\build\intermediates\merged_native_libs\debug' -Recurse -Filter 'libc++_shared.so' |
    Where-Object { $_.FullName -match 'arm64-v8a' } | Select-Object -First 1
if (-not $cppLibrary) { throw 'arm64 libc++_shared.so missing.' }
Invoke-Checked $Adb @('-s',$Serial,'push',$cppLibrary.FullName,"$deviceDir/") (Join-Path $evidenceRoot 'push-cpp.log')
Invoke-Checked $Adb @('-s',$Serial,'shell',"cd $deviceDir && LD_LIBRARY_PATH=. ./bncam_single_frame_qualification $Fixture numerical") (Join-Path $evidenceRoot 'numerical.log')
Invoke-Checked $Adb @('-s',$Serial,'install','-r','-g','app/build/outputs/apk/debug/app-debug.apk') (Join-Path $evidenceRoot 'install-camera.log')
Invoke-Checked $Adb @('-s',$Serial,'exec-out','run-as','com.bncam','cat','files/terminal_backpressure.json') (Join-Path $evidenceRoot 'terminal-backpressure.json')
Invoke-Checked $Adb @('-s',$Serial,'shell','am','start','-W','-n','com.bncam/.MainActivity') (Join-Path $evidenceRoot 'launch.log')
Invoke-Checked 'python' @('scripts\single_frame_qualification.py','capture','--adb',$Adb,'--serial',$Serial,'--scene','CURRENT_UNCONTROLLED') (Join-Path $evidenceRoot 'camera-smoke.log')
Invoke-Checked 'python' @('scripts\single_frame_qualification.py','stress','--adb',$Adb,'--serial',$Serial,'--scene','BACKPRESSURE') (Join-Path $evidenceRoot 'camera-stress.log')
Invoke-Checked $Adb @('-s',$Serial,'shell','am','force-stop','com.bncam') (Join-Path $evidenceRoot 'stop-camera-before-benchmark.log')
$cooldownDeadline = (Get-Date).AddMinutes(5)
$coolSamples = 0
while ($coolSamples -lt 2) {
    $thermalText = & $Adb -s $Serial shell dumpsys thermalservice
    if ($LASTEXITCODE -ne 0) { throw 'Thermal service query failed.' }
    $thermalMatch = [regex]::Match(($thermalText -join "`n"), '(?m)^Thermal Status: (\d+)')
    if (-not $thermalMatch.Success) { throw 'Thermal status unavailable; benchmark refused.' }
    $thermalStatus = [int]$thermalMatch.Groups[1].Value
    "$(Get-Date -Format o) status=$thermalStatus" | Add-Content (Join-Path $evidenceRoot 'thermal-cooldown.log')
    if ($thermalStatus -eq 0) { $coolSamples++ } else { $coolSamples = 0 }
    if ((Get-Date) -gt $cooldownDeadline) { throw 'Thermal cooldown exceeded five minutes; benchmark refused.' }
    if ($coolSamples -lt 2) { Start-Sleep -Seconds 10 }
}
Invoke-Checked $Adb @('-s',$Serial,'shell',"cd $deviceDir && LD_LIBRARY_PATH=. ./bncam_single_frame_qualification $Fixture benchmark $MeasuredRuns") (Join-Path $evidenceRoot 'benchmark.log')
$fixtureEvidence = Join-Path $evidenceRoot ("fixture-" + [guid]::NewGuid().ToString('N'))
# A nonexistent destination prevents adb pull from nesting a new dataset inside an old one.
Invoke-Checked $Adb @('-s',$Serial,'pull',$Fixture,$fixtureEvidence) (Join-Path $evidenceRoot 'pull-fixture.log')
$fixtureEvidence | Set-Content (Join-Path $evidenceRoot 'fixture-directory.txt')
Invoke-Checked 'python' @('scripts\single_frame_qualification.py','benchmark','--directory',$fixtureEvidence) (Join-Path $evidenceRoot 'benchmark-summary.log')
$benchmarkSummary = Get-Content -LiteralPath 'work\single-frame-qualification\warm-benchmark-summary.json' -Raw | ConvertFrom-Json
foreach ($pathResult in $benchmarkSummary.PSObject.Properties | Where-Object { $_.Name -match '^[012]$' }) {
    if (-not $pathResult.Value.rankingQualified) {
        throw "Benchmark not qualified for $($pathResult.Value.path): thermal/unknown timing/determinism. Measurements were preserved."
    }
}
Invoke-Checked 'git' @('diff','--check') (Join-Path $evidenceRoot 'diff-check-final.txt')
Write-Output "SINGLE-FRAME GATE PASSED. Evidence: $evidenceRoot. Physical scene qualification remains operator-owned."
