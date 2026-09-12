# Shows what a Cloudflare "Billing Read" API token can read, without printing any sensitive value.
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-billing-token.ps1
# The token is typed into a hidden prompt, used for two GET requests, and dropped. Nothing is stored.
$ErrorActionPreference = 'Stop'
$secure = Read-Host 'Paste the Billing Read API token (input is hidden)' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
$headers = @{ Authorization = "Bearer $token" }
$token = $null

function Show-Presence($object, $name) {
    $value = $object.$name
    if ($null -eq $value -or [string]::IsNullOrEmpty([string]$value)) { "  $name : absent or empty" } else { "  $name : present" }
}

Write-Output 'Checking GET /user/billing/profile ...'
try {
    $r = Invoke-RestMethod -Uri 'https://api.cloudflare.com/client/v4/user/billing/profile' -Headers $headers -Method Get
} catch {
    $status = 'unknown'
    if ($_.Exception.Response) { $status = $_.Exception.Response.StatusCode.value__ }
    Write-Output "  HTTP $status : this token cannot read the billing profile."
    $r = $null
}
if ($r -and $r.success) {
    $p = $r.result
    $names = @($p | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name)
    Write-Output ("  readable. {0} fields returned: {1}" -f $names.Count, ($names -join ', '))
    $card = [string]$p.card_number
    if ([string]::IsNullOrEmpty($card)) {
        Write-Output '  card_number : absent or empty'
    } else {
        $digits = ($card -replace '[^0-9]', '').Length
        $hasMask = $card -match '[xX\*]'
        if ($hasMask -or $digits -le 4) { Write-Output "  card_number : masked, $digits digits visible" }
        else { Write-Output "  card_number : WARNING, $digits digits returned and no mask characters, looks unmasked" }
    }
    Show-Presence $p 'card_expiry_month'
    Show-Presence $p 'card_expiry_year'
    Show-Presence $p 'payment_email'
    Show-Presence $p 'payment_address'
    Show-Presence $p 'telephone'
    Show-Presence $p 'vat'
} elseif ($r) {
    Write-Output '  API answered but success=false.'
}

Write-Output 'Checking GET /user/invoices ...'
try {
    $inv = Invoke-RestMethod -Uri 'https://api.cloudflare.com/client/v4/user/invoices' -Headers $headers -Method Get
    if ($inv.success) { Write-Output ("  readable. {0} invoice records listed." -f @($inv.result).Count) } else { Write-Output '  API answered but success=false.' }
} catch {
    $status = 'unknown'
    if ($_.Exception.Response) { $status = $_.Exception.Response.StatusCode.value__ }
    Write-Output "  HTTP $status : this token cannot list invoices."
}
$headers = $null
Write-Output 'Done. Nothing above is secret; it is safe to share this output.'
