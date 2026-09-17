param([Parameter(Mandatory=$true)][string]$Apk)
# Release safety gate. Refuses any APK that would ship a broken or rejected update:
#  - the APK's internal versionCode must equal app/build.gradle (never an old APK under a new label)
#  - the APK must be signed with the SAME certificate as the published app, or the in-app
#    updater's signer check rejects it and installs nothing.
# Native tools are run through cmd with output captured to a file, so a stderr line never
# trips Windows PowerShell 5.1's stop-on-native-error behavior.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$bt = Join-Path $repoRoot '.tools/sdk/build-tools/35.0.0'
$aapt = Join-Path $bt 'aapt.exe'
$apksigner = Join-Path $bt 'apksigner.bat'
# The published signing certificate. Changing the signing key would strand every installed
# app on the old key, so this digest is pinned; update it only on a deliberate key rotation.
$expectedSigner = '1050743d7c8f8ee1c25040f8bd63661d8eebc050cad442ea3ad20bf68e41ff2e'

if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $aapt)) { throw "aapt not found at $aapt (is the local SDK in .tools/sdk?)." }
if (-not (Test-Path -LiteralPath $apksigner)) { throw "apksigner not found at $apksigner." }

$gradle = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle') -Raw
if ($gradle -notmatch 'versionCode\s+(\d+)') { throw 'Could not read versionCode from app/build.gradle.' }
$gradleCode = [int]$Matches[1]

$tmp = [IO.Path]::GetTempFileName()
cmd /c "`"$aapt`" dump badging `"$Apk`" > `"$tmp`" 2>&1"
$badging = Get-Content -LiteralPath $tmp -Raw; Remove-Item -LiteralPath $tmp -Force
if ($badging -notmatch "versionCode='(\d+)'") { throw 'Could not read versionCode from the APK.' }
$apkCode = [int]$Matches[1]
if ($apkCode -ne $gradleCode) {
    throw "APK versionCode ($apkCode) does not match app/build.gradle ($gradleCode). Rebuild before publishing."
}

$tmp = [IO.Path]::GetTempFileName()
cmd /c "`"$apksigner`" verify --print-certs `"$Apk`" > `"$tmp`" 2>&1"
$certs = Get-Content -LiteralPath $tmp -Raw; Remove-Item -LiteralPath $tmp -Force
if ($certs -notmatch 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})') { throw "APK is not validly signed, or the certificate is unreadable." }
$digest = $Matches[1].ToLowerInvariant()
if ($digest -ne $expectedSigner) {
    throw "APK signer certificate ($digest) is not the published key ($expectedSigner). The in-app updater would reject this APK. Aborting."
}

Write-Output "verify-apk: OK  versionCode=$apkCode  signer=$digest"
