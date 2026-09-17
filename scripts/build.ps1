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
    # Run Gradle through cmd so a stderr line (e.g. javac's deprecation note) is not mistaken
    # for a failure under Windows PowerShell 5.1; trust the real exit code instead.
    $log = Join-Path $projectRoot '.tools/build.log'
    cmd /c ".\gradlew.bat --console=plain :app:assembleRelease :app:testDebugUnitTest :app:lintRelease > `"$log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $log -Tail 40; throw 'Build or checks failed' }
    New-Item -ItemType Directory -Force -Path ./releases | Out-Null
    Copy-Item -LiteralPath ./app/build/outputs/apk/release/app-release.apk -Destination ./releases/UsageWidget.apk -Force
    $hash = (Get-FileHash -LiteralPath ./releases/UsageWidget.apk -Algorithm SHA256).Hash.ToUpperInvariant()
    "$hash  UsageWidget.apk" | Out-File -FilePath ./releases/SHA256SUMS.txt -Encoding ascii -NoNewline
    Write-Output "Built releases/UsageWidget.apk  sha256=$hash"
    if ($Install) {
        & "$env:ANDROID_HOME/platform-tools/adb.exe" install -r ./releases/UsageWidget.apk
        if ($LASTEXITCODE -ne 0) { throw 'ADB installation failed; check USB debugging and device authorization.' }
    }
} finally { Pop-Location }
