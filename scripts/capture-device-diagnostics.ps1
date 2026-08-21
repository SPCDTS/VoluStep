[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
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
Save-AdbOutput 'package.txt' @('shell', 'dumpsys', 'package', 'dev.spcdts.volumemapper.debug')
Save-AdbOutput 'logcat.txt' @('logcat', '-d', '-v', 'threadtime')

Write-Host "诊断已保存到 $outputDirectory"
