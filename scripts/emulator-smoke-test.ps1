[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [switch]$SkipBuild
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if ($Serial -notmatch '^emulator-\d+$') {
    throw '此脚本会修改无障碍设置，只允许显式指定 emulator-* 序列号；真机请按 docs/TESTING.md 手动测试。'
}
if (-not $SkipBuild) {
    & (Join-Path $script:ProjectRoot 'gradlew.bat') :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& $adb -s $Serial wait-for-device
$booted = ''
for ($attempt = 0; $attempt -lt 120 -and $booted.Trim() -ne '1'; $attempt++) {
    Start-Sleep -Seconds 1
    $booted = & $adb -s $Serial shell getprop sys.boot_completed
}
if ($booted.Trim() -ne '1') { throw '模拟器在 120 秒内未完成启动。' }

$apk = Join-Path $script:ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw '安装 debug APK 失败。' }

$component = 'dev.spcdts.volumemapper.debug/dev.spcdts.volumemapper.runtime.VolumeKeyAccessibilityService'
$enabled = (& $adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$entries = @($enabled.Split(':') | Where-Object { $_ -and $_ -ne 'null' })
if ($entries -notcontains $component) { $entries += $component }
& $adb -s $Serial shell settings put secure enabled_accessibility_services ($entries -join ':')
& $adb -s $Serial shell settings put secure accessibility_enabled 1
& $adb -s $Serial shell am start -n 'dev.spcdts.volumemapper.debug/dev.spcdts.volumemapper.MainActivity'

Write-Host '应用和无障碍服务已在模拟器启用。请在界面同意披露并启动控制器。'
Write-Host '准备好后可手动验证：adb -s SERIAL shell input keyevent 24 / 25'
