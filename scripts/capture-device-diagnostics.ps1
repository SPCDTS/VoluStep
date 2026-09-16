[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [ValidateSet('dev.spcdts.volumemapper', 'dev.spcdts.volumemapper.debug')]
    [string]$PackageName = 'dev.spcdts.volumemapper'
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$adbName = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'adb.exe' } else { 'adb' }
$adb = Join-Path $env:ANDROID_HOME "platform-tools/$adbName"
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputDirectory = Join-Path $script:ProjectRoot "artifacts\device-$timestamp"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$utf8NoBom = [Text.UTF8Encoding]::new($false)

function Save-AdbOutput([string]$FileName, [string[]]$Arguments) {
    $content = (& $adb -s $Serial @Arguments 2>&1) -join [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $outputDirectory $FileName), $content, $utf8NoBom)
}

Save-AdbOutput 'device.txt' @('shell', 'getprop')
Save-AdbOutput 'audio.txt' @('shell', 'dumpsys', 'audio')
Save-AdbOutput 'accessibility.txt' @('shell', 'dumpsys', 'accessibility')
Save-AdbOutput 'package.txt' @('shell', 'dumpsys', 'package', $PackageName)
Save-AdbOutput 'power.txt' @('shell', 'dumpsys', 'power')
Save-AdbOutput 'key-delivery.txt' @('shell', 'dumpsys', 'activity', 'service', "$PackageName/dev.spcdts.volumemapper.runtime.VolumeKeyAccessibilityService")
Save-AdbOutput 'logcat.txt' @('logcat', '-d', '-v', 'threadtime')

Write-Host "诊断已保存到 $outputDirectory"
