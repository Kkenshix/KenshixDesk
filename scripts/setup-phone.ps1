[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Adb,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$ApkSigner,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$SignerSha256,
    [ValidatePattern('^[A-Za-z0-9-]+$')][string]$Serial,
    [switch]$TestOnly
)

$ErrorActionPreference = 'Stop'
$adbExe = (Resolve-Path -LiteralPath $Adb).Path
$apkFile = (Resolve-Path -LiteralPath $Apk).Path
$signer = (Resolve-Path -LiteralPath $ApkSigner).Path

$signature = & $signer verify --print-certs $apkFile 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK imzası doğrulanamadı.' }
$certificates = @($signature | ForEach-Object { [string]$_ } |
    Select-String '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$')
if ($certificates.Count -ne 1 -or $certificates[0].Matches[0].Groups[1].Value -ne $SignerSha256) {
    throw 'APK beklenen tek imzalayıcı ile eşleşmiyor.'
}

$list = & $adbExe devices -l
if ($LASTEXITCODE -ne 0) { throw 'ADB cihaz listesi alınamadı.' }
$devices = @($list | ForEach-Object {
    if ($_ -match '^(?<id>[A-Za-z0-9-]+)\s+(?<state>device|unauthorized|offline)(?:\s|$)') {
        [PSCustomObject]@{ Serial = $Matches.id; State = $Matches.state }
    }
})
if ($Serial) { $devices = @($devices | Where-Object Serial -eq $Serial) }
if ($devices.Count -ne 1) { throw 'Tek bir USB cihazı belirlenemedi. Gerekirse -Serial kullan.' }
$device = $devices[0]
if ($device.State -eq 'unauthorized') {
    throw 'Telefonun kilidini aç ve USB hata ayıklama isteğinde bu PC için İzin ver seçeneğine dokun.'
}
if ($device.State -ne 'device') { throw 'Telefon ADB üzerinden hazır değil.' }
$Serial = $device.Serial

function Invoke-Phone {
    $response = & $adbExe -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB işlemi başarısız oldu: $($args[0])" }
    $response
}

$model = (Invoke-Phone shell getprop ro.product.model).Trim()
$release = (Invoke-Phone shell getprop ro.build.version.release).Trim()
$sdk = (Invoke-Phone shell getprop ro.build.version.sdk).Trim()
if ($model -notmatch '^SM-M225[A-Z0-9]*$' -or $sdk -notmatch '^\d+$' -or [int]$sdk -lt 30) {
    throw "Beklenen Galaxy M22 ve uyumlu Android sürümü bulunamadı ($model, Android $release)."
}
Write-Host "Galaxy M22 ($model), Android $release, SDK $sdk"
$installFlags = @('-r')
if ($TestOnly) { $installFlags += '-t' }
$installation = Invoke-Phone install @installFlags $apkFile
if ($installation -notcontains 'Success') { throw 'Android kurulum başarısını bildirmedi.' }
$reverse = Invoke-Phone reverse --list
if ($reverse -notmatch '\btcp:43851 tcp:43851\b') {
    Invoke-Phone reverse --no-rebind tcp:43851 tcp:43851 | Out-Null
    $reverse = Invoke-Phone reverse --list
}
if ($reverse -notmatch '\btcp:43851 tcp:43851\b') { throw 'USB port eşlemesi doğrulanamadı.' }
Invoke-Phone shell am start -n com.kenshix.deck/.MainActivity
Invoke-Phone shell dumpsys package com.kenshix.deck | Select-String 'versionName=|versionCode=' | Select-Object -First 2
