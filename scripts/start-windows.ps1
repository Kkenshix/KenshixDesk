[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Exe,
    [string]$Adb,
    [ValidatePattern('^[A-Za-z0-9-]+$')][string]$Serial,
    [ipaddress]$Lan,
    [switch]$StartAtLogon
)

$ErrorActionPreference = 'Stop'
$binary = (Resolve-Path -LiteralPath $Exe).Path
$arguments = @()
if ($Adb -or $Serial) {
    if (!$Adb -or !$Serial) { throw 'ADB yolu ve USB seri numarası birlikte gerekli.' }
    $adbPath = (Resolve-Path -LiteralPath $Adb).Path
    $arguments += @('--adb', ('"{0}"' -f $adbPath), $Serial)
}

if ($Lan) {
    $ip = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $Lan.IPAddressToString -ErrorAction Stop
    $profiles = @(Get-NetConnectionProfile -InterfaceIndex $ip.InterfaceIndex)
    if ($profiles.Count -ne 1 -or $profiles[0].NetworkCategory -ne 'Private') {
        throw 'Wi-Fi bağlantısı için seçilen Windows ağ profili Özel olmalı.'
    }
    $arguments += @('--lan', $Lan.IPAddressToString)
}

if ($StartAtLogon) {
    $startup = [Environment]::GetFolderPath([Environment+SpecialFolder]::Startup)
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $startup 'KenshixDeck.lnk'))
    $shortcut.TargetPath = $binary
    $shortcut.Arguments = $arguments -join ' '
    $shortcut.WorkingDirectory = (Split-Path -Parent $binary)
    $shortcut.WindowStyle = 7
    $shortcut.Save()
}

$start = @{ FilePath = $binary; WindowStyle = 'Hidden'; PassThru = $true }
if ($arguments.Count -gt 0) { $start.ArgumentList = $arguments -join ' ' }
$hostProcess = Start-Process @start
Start-Sleep -Milliseconds 850
if ($hostProcess.HasExited) { throw 'KenshixDeck başlatılamadı. Gerekirse EXE dosyasını terminalden çalıştır.' }
Write-Host 'KenshixDeck sistem tepsisinden yönetilebilir.'
