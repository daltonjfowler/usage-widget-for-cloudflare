$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$signingRoot = Join-Path $projectRoot 'private'
$signingKey = Join-Path $signingRoot 'release.jks'
if (Test-Path -LiteralPath $signingKey) { return }
New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
$keypassFile = Join-Path $signingRoot 'keypass.txt'
if (-not (Test-Path -LiteralPath $keypassFile)) {
    $randomBytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($randomBytes)
    $rng.Dispose()
    [IO.File]::WriteAllText($keypassFile, [Convert]::ToBase64String($randomBytes))
}
& "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $signingKey -alias usage-widget -keyalg RSA -keysize 3072 -validity 10000 -storepass:file $keypassFile -keypass:file $keypassFile -dname 'CN=Usage Widget Personal Build' -noprompt
if ($LASTEXITCODE -ne 0) { throw 'Could not create the local APK signing key.' }
Write-Output 'Personal signing key created in private/. Keep that folder for future APK updates.'
