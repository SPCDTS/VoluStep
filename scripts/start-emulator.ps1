[CmdletBinding()]
param(
    [ValidateSet('28', '37')]
    [string]$Api = '37',
    [switch]$Headless,
    [switch]$ColdBoot
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$avdName = "VolumeMapper_API_$Api"
$arguments = @('-avd', $avdName, '-no-snapshot-save')
if ($Headless) { $arguments += @('-no-window', '-no-audio', '-gpu', 'swiftshader_indirect') }
if ($ColdBoot) { $arguments += '-no-snapshot-load' }

if ($Headless) {
    Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden
} else {
    Start-Process -FilePath $emulator -ArgumentList $arguments
}
Write-Host "已启动 $avdName"
