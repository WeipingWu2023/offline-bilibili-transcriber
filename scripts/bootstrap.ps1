param([switch]$InstallSdk)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force $toolsRoot | Out-Null
function Download([string]$url, [string]$path) {
    if (!(Test-Path -LiteralPath $path)) {
        $partial = "$path.download"
        & curl.exe --fail --location --retry 2 --connect-timeout 30 --max-time 600 --output $partial $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
        Move-Item -LiteralPath $partial -Destination $path -Force
    }
}
$gradleZip = Join-Path $toolsRoot 'gradle-8.11.1-bin.zip'
Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' $gradleZip
Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256' "$gradleZip.sha256"
$expected = (Get-Content -LiteralPath "$gradleZip.sha256" -Raw).Trim()
if ((Get-FileHash -LiteralPath $gradleZip -Algorithm SHA256).Hash -ine $expected) { throw 'Gradle checksum mismatch' }
if (!(Test-Path "$toolsRoot\gradle-8.11.1\bin\gradle.bat")) { Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolsRoot }
$jdkZip = Join-Path $toolsRoot 'jdk17.zip'
Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip
if (!(Test-Path "$toolsRoot\jdk")) { Expand-Archive -LiteralPath $jdkZip -DestinationPath "$toolsRoot\jdk" }
$jdkPath = (Get-ChildItem "$toolsRoot\jdk" -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkPath
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-cache'
if ($InstallSdk) {
    $sdkRoot = Join-Path $toolsRoot 'android-sdk'
    $cliZip = Join-Path $toolsRoot 'commandlinetools.zip'
    Download 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $cliZip
    if (!(Test-Path "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat")) {
        New-Item -ItemType Directory -Force "$sdkRoot\cmdline-tools" | Out-Null
        Expand-Archive -LiteralPath $cliZip -DestinationPath "$toolsRoot\android-cli"
        Move-Item -LiteralPath "$toolsRoot\android-cli\cmdline-tools" -Destination "$sdkRoot\cmdline-tools\latest"
    }
    Write-Host 'Please review and accept the Android SDK licenses interactively.'
    & "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdkRoot" --licenses
    & "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdkRoot" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
    $escapedSdk = $sdkRoot.Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath "$projectRoot\local.properties" -Encoding ascii -Value "sdk.dir=$escapedSdk"
}
Write-Host "JDK: $jdkPath"
Write-Host 'Next: .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug'
