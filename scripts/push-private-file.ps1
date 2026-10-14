param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Name
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$' -or $Name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid device or app filename' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$localFile = (Resolve-Path -LiteralPath $Source).Path
$remoteStage = '/data/local/tmp/offline-fixture-' + [guid]::NewGuid().ToString('N')
try {
    & $adbPath -s $Serial push $localFile $remoteStage
    if ($LASTEXITCODE -ne 0) { throw 'ADB push failed' }
    & $adbPath -s $Serial shell run-as com.example.captureprobe cp $remoteStage "files/$Name"
    if ($LASTEXITCODE -ne 0) { throw 'Copy to debug app failed' }
    $remoteHash = & $adbPath -s $Serial shell run-as com.example.captureprobe sha256sum "files/$Name"
    if ($LASTEXITCODE -ne 0) { throw 'Device hash failed' }
    $expected = (Get-FileHash -LiteralPath $localFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if (($remoteHash -split '\s+')[0] -ne $expected) { throw 'Device fixture SHA256 mismatch' }
} finally {
    & $adbPath -s $Serial shell rm -f $remoteStage
}
Write-Output "Copied and verified $Name in our debug app (no root)."
