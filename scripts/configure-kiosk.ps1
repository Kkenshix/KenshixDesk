[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Adb,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$Serial,
    [switch]$SamsungGestures
)

$ErrorActionPreference = 'Stop'
$adbExe = (Resolve-Path -LiteralPath $Adb).Path
function Invoke-Phone {
    $response = & $adbExe -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB işlemi başarısız oldu: $($args[0]) $($args[1])" }
    $response
}

if ((Invoke-Phone get-state).Trim() -ne 'device') { throw 'Telefon USB ADB üzerinden hazır değil.' }
$model = (Invoke-Phone shell getprop ro.product.model).Trim()
if ($model -notmatch '^SM-M225[A-Z0-9]*$') { throw 'Seçilen aygıt bir Galaxy M22 değil.' }

$component = 'com.kenshix.deck/.DeckAdminReceiver'
$owner = (Invoke-Phone shell dpm list-owners) -join "`n"
if ($owner -notmatch 'com\.kenshix\.deck/.*DeckAdminReceiver') {
    if ($owner.Trim() -ne 'no owners') { throw 'Cihazda mevcut bir Android yöneticisi var.' }
    $users = @(Invoke-Phone shell pm list users | Select-String 'UserInfo\{')
    if ($users.Count -ne 1 -or $users[0] -notmatch 'UserInfo\{0:') {
        throw 'Cihazda birden fazla Android kullanıcısı veya iş profili var.'
    }
    $counts = @(Invoke-Phone shell dumpsys account | Select-String '^\s*Accounts:\s*(\d+)\s*$')
    if ($counts.Count -ne 1) { throw 'Telefonun hesap sayısı doğrulanamadı.' }
    $count = [int]$counts[0].Matches[0].Groups[1].Value
    if ($count -ne 0) { throw "Android cihaz yönetimi için hesap kaldırılması gerekiyor. Bu cihazda $count hesap var." }
    $provisioned = Invoke-Phone shell dpm set-device-owner $component
    $owner = (Invoke-Phone shell dpm list-owners) -join "`n"
    if ($owner -notmatch 'com\.kenshix\.deck/.*DeckAdminReceiver') {
        throw 'Android cihaz yöneticisini doğrulamadı.'
    }
    $provisioned
}

if ($SamsungGestures) {
    $overlay = 'com.samsung.internal.systemui.navbar.gestural_no_hint'
    $state = (Invoke-Phone shell cmd overlay dump $overlay) -join "`n"
    if ($state -notmatch 'mCategory\.*: com.android.internal.navigation_bar_mode') {
        throw 'Bu Samsung yazılımında işaretsiz gezinme modu doğrulanamadı.'
    }
    Invoke-Phone shell cmd overlay enable-exclusive --user 0 --category $overlay
    $enabled = @(Invoke-Phone shell cmd overlay list --user 0 | Select-String ('^\[x\] ' + [regex]::Escape($overlay) + '$'))
    if ($enabled.Count -ne 1) { throw 'Samsung hareket modu etkinleştirilemedi.' }
}

Invoke-Phone shell am start -n com.kenshix.deck/.MainActivity
Write-Host 'Telefonun KenshixDeck menüsünde Kiosk → Kiosku aç seçeneğini kullan.'
