param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('control','bilibili')][string]$Label = 'control',
    [string]$Recording = ''
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
$names = @(& $adbPath -s $Serial shell run-as com.example.captureprobe ls files/recordings)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read our debug app recordings; first complete a recording' }
$latest = $names | ForEach-Object { $_.Trim() } |
    Where-Object { $_ -match '^capture-[0-9]+-[a-f0-9]{8}(-interrupted)?\.wav$' } |
    Sort-Object -Descending | Select-Object -First 1
if (!$latest) { throw 'No finalized WAV; stop recording first' }
if ($Recording) {
    if ($Recording -notmatch '^capture-[0-9]+-[a-f0-9]{8}(-interrupted)?\.wav$' -or $names -notcontains $Recording) {
        throw 'Specified recording is not a finalized WAV in our app'
    }
    $latest = $Recording
}
$evidenceDir = Join-Path $projectRoot ("evidence\" + $Label + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $evidenceDir | Out-Null
function ReadAppFile([string]$remoteName, [string]$destination) {
    if ($remoteName -notmatch '^capture-[0-9]+-[a-f0-9]{8}(-interrupted)?\.(wav|log)$') { throw 'Invalid app filename' }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $adbPath
    $process.StartInfo.Arguments = "-s $Serial exec-out run-as com.example.captureprobe cat files/recordings/$remoteName"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.Start() | Out-Null
    $output = [System.IO.File]::Create($destination)
    try { $process.StandardOutput.BaseStream.CopyTo($output) } finally { $output.Dispose() }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    $process.Dispose()
    if ($exitCode -ne 0) { throw "Evidence read failed: $errorText" }
}
ReadAppFile $latest (Join-Path $evidenceDir $latest)
$logName = $latest.Replace('-interrupted.wav','.log').Replace('.wav','.log')
ReadAppFile $logName (Join-Path $evidenceDir $logName)
Write-Output $evidenceDir
