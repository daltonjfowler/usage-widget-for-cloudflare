param([string]$Notes = "", [switch]$NoDeploy)
# One command to take the working tree to the phone, safely.
#   scripts/ship.ps1 -Notes "What changed in this version"
#
# Steps, each of which fails loud and stops:
#   1. build.ps1   -- compile, run the full unit-test gate + lint, sign with private/release.jks,
#                     stage releases/UsageWidget.apk + releases/SHA256SUMS.txt (local .tools JDK/SDK).
#   2. publish.ps1 -- verify the APK (versionCode matches build.gradle, signer matches the published
#                     key, no downgrade), write latest.json, and (unless -NoDeploy) deploy the Worker
#                     and confirm the live endpoint serves the new version_code.
#
# Before shipping a new version, bump versionCode AND versionName in app/build.gradle -- publish
# refuses to re-publish or downgrade. Deploy needs wrangler logged in once (npx wrangler login),
# which stays on the machine; no secret is passed here.
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/build.ps1"
if ($LASTEXITCODE -ne 0) { throw 'build.ps1 failed.' }
if ($NoDeploy) { & "$PSScriptRoot/publish.ps1" -NoDeploy -Notes $Notes }
else { & "$PSScriptRoot/publish.ps1" -Notes $Notes }
if ($LASTEXITCODE -ne 0) { throw 'publish.ps1 failed.' }
