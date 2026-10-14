param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Remote,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$' -or $Remote -notmatch '^files/(debug-last-run|device-engine-result)\.json$') {
    throw 'Only our debug app result files are permitted'
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$process = New-Object System.Diagnostics.Process
$process.StartInfo.FileName = $adbPath
$process.StartInfo.Arguments = "-s $Serial exec-out run-as com.example.captureprobe cat $Remote"
$process.StartInfo.UseShellExecute = $false
$process.StartInfo.CreateNoWindow = $true
$process.StartInfo.RedirectStandardOutput = $true
$process.StartInfo.RedirectStandardError = $true
$process.Start() | Out-Null
$stream = [System.IO.File]::Create((Join-Path $projectRoot $Output))
try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
$errorText = $process.StandardError.ReadToEnd()
$process.WaitForExit()
$exitCode = $process.ExitCode
$process.Dispose()
if ($exitCode -ne 0) { throw "Read result failed: $errorText" }
Write-Output "Saved $Remote to $Output"
