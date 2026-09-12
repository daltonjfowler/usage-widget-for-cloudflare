param([switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkRoot = Join-Path $projectRoot '.tools/jdk'
if (Test-Path -LiteralPath $jdkRoot) { $env:JAVA_HOME = (Get-ChildItem -Directory -LiteralPath $jdkRoot | Select-Object -First 1).FullName }
if (Test-Path -LiteralPath "$projectRoot/.tools/sdk") { $env:ANDROID_HOME = "$projectRoot/.tools/sdk" }
$env:ANDROID_USER_HOME = "$projectRoot/.tools/android-user"
$env:GRADLE_USER_HOME = "$projectRoot/.tools/gradle-home"
Push-Location $projectRoot
try {
    & "$PSScriptRoot/ensure-signing.ps1"
    & ./gradlew.bat --console=plain :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed' }
    New-Item -ItemType Directory -Force -Path ./releases | Out-Null
    Copy-Item -LiteralPath ./app/build/outputs/apk/release/app-release.apk -Destination ./releases/UsageWidget.apk -Force
    Get-FileHash -LiteralPath ./releases/UsageWidget.apk -Algorithm SHA256
    if ($Install) {
        & "$env:ANDROID_HOME/platform-tools/adb.exe" install -r ./releases/UsageWidget.apk
        if ($LASTEXITCODE -ne 0) { throw 'ADB installation failed; check USB debugging and device authorization.' }
    }
} finally { Pop-Location }
