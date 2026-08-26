$ErrorActionPreference = "Stop"

$Version = "3.3.0"
$ExpectedGitBlobSha = "8df03649b5b97acc1e43839d2857d32d267958ca"
$Root = Split-Path -Parent $PSScriptRoot
$Destination = Join-Path $Root "src/main/cpp/third_party/vma-3.3.0/vk_mem_alloc.h"
$Url = "https://raw.githubusercontent.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator/v3.3.0/include/vk_mem_alloc.h"

Write-Host "Fetching Vulkan Memory Allocator v$Version..."
Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination

$Bytes = [System.IO.File]::ReadAllBytes($Destination)
$Prefix = [System.Text.Encoding]::UTF8.GetBytes("blob $($Bytes.Length)`0")
$Payload = New-Object byte[] ($Prefix.Length + $Bytes.Length)
[System.Buffer]::BlockCopy($Prefix, 0, $Payload, 0, $Prefix.Length)
[System.Buffer]::BlockCopy($Bytes, 0, $Payload, $Prefix.Length, $Bytes.Length)
$Sha1 = [System.Security.Cryptography.SHA1]::Create()
try {
    $ActualGitBlobSha = -join ($Sha1.ComputeHash($Payload) | ForEach-Object { $_.ToString("x2") })
} finally {
    $Sha1.Dispose()
}

$Content = [System.Text.Encoding]::UTF8.GetString($Bytes)
if ($ActualGitBlobSha -ne $ExpectedGitBlobSha -or $Content -notmatch '<b>Version 3\.3\.0</b>') {
    Remove-Item -Force $Destination
    throw "Downloaded VMA header did not match the pinned v$Version upstream blob."
}
Write-Host "VMA header ready and verified: $Destination"
