param([switch]$NoDeploy, [string]$Notes = "")
# Publish the current release build to the Usage Widget update host. Run from the repo
# root AFTER a green scripts/build.ps1, which produces releases/UsageWidget.apk and
# releases/SHA256SUMS.txt. This script never builds; it only stages latest.json and
# the APK into updates/public and (unless -NoDeploy) deploys the Worker.
#
#   scripts/build.ps1
#   scripts/publish.ps1 -Notes "In-app updates"
#
# -NoDeploy stops before wrangler, so the latest.json half can be proven without
# touching Cloudflare. wrangler must already be logged in (npm install inside
# updates/ once gives Dalton the local CLI).
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$apk = Join-Path $repoRoot 'releases/UsageWidget.apk'
$sums = Join-Path $repoRoot 'releases/SHA256SUMS.txt'
$updatesDir = Join-Path $repoRoot 'updates'
$publicDir = Join-Path $updatesDir 'public'

if (-not (Test-Path -LiteralPath $apk)) { throw "No release APK at releases/UsageWidget.apk. Run scripts/build.ps1 first." }
if (-not (Test-Path -LiteralPath $sums)) { throw "No releases/SHA256SUMS.txt. Run scripts/build.ps1 first." }

# The APK must match its recorded checksum, or the build and the sums file are out of
# step and publishing would ship a mismatched download. Fail loudly.
$actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToUpperInvariant()
$recorded = (((Get-Content -LiteralPath $sums -Raw) -split '\s+') | Where-Object { $_ })[0].ToUpperInvariant()
if ($actual -ne $recorded) {
    throw "releases/SHA256SUMS.txt ($recorded) does not match releases/UsageWidget.apk ($actual). Re-run scripts/build.ps1."
}

# versionCode and versionName come from app/build.gradle, the one source of truth.
$gradle = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle') -Raw
if ($gradle -notmatch 'versionCode\s+(\d+)') { throw 'Could not read versionCode from app/build.gradle.' }
$versionCode = [int]$Matches[1]
if ($gradle -notmatch "versionName\s+'([^']+)'") { throw 'Could not read versionName from app/build.gradle.' }
$versionName = $Matches[1]

$size = (Get-Item -LiteralPath $apk).Length

# Stage the APK into the public asset folder (never committed).
New-Item -ItemType Directory -Force -Path $publicDir | Out-Null
Copy-Item -LiteralPath $apk -Destination (Join-Path $publicDir 'UsageWidget.apk') -Force

# Write latest.json by hand so the shape and the uppercase hex are exact. Notes is a
# one-line string; escape the two characters that would break the JSON.
$notesEscaped = $Notes.Replace('\', '\\').Replace('"', '\"')
$json = '{"version_code": ' + $versionCode + ', "version_name": "' + $versionName + '", "sha256": "' + $actual + '", "size": ' + $size + ', "apk": "UsageWidget.apk", "notes": "' + $notesEscaped + '"}'
$json | Out-File -FilePath (Join-Path $publicDir 'latest.json') -Encoding ascii -NoNewline
Write-Output "Wrote updates/public/latest.json: version_code $versionCode, version_name $versionName, size $size bytes"
Write-Output "sha256 $actual"

if ($NoDeploy) {
    Write-Output 'NoDeploy set: stopping before wrangler. latest.json and UsageWidget.apk are staged in updates/public.'
    return
}

Push-Location $updatesDir
try {
    & npx wrangler deploy
    if ($LASTEXITCODE -ne 0) { throw 'wrangler deploy failed.' }
} finally { Pop-Location }
Write-Output 'Deployed. Confirm https://usagewidget-updates.daltonjfowler.workers.dev/latest.json shows this version_code.'
