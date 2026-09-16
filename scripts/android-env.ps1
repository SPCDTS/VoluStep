[CmdletBinding()]
param()

$script:ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$isWindowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$javaName = if ($isWindowsHost) { 'java.exe' } else { 'java' }
$javaCandidates = @($env:JAVA_HOME, (Join-Path $script:ProjectRoot '.toolchains/android-studio/jbr'))
$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $script:ProjectRoot '.toolchains/android-sdk'))
if ($isWindowsHost) {
    $javaCandidates += 'C:/Program Files/Android/Android Studio/jbr'
    if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
}
$selectedJbr = $javaCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin/$javaName")) } | Select-Object -First 1
$selectedSdk = $sdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platform-tools')) } | Select-Object -First 1
if (-not $selectedJbr) { throw '未找到 Java，请配置 JAVA_HOME 或安装仓库工具链。' }
if (-not $selectedSdk) { throw '未找到 Android SDK，请配置 ANDROID_HOME 或安装仓库工具链。' }
$env:JAVA_HOME = $selectedJbr
$env:ANDROID_HOME = $selectedSdk
$env:ANDROID_SDK_ROOT = $selectedSdk
$androidPaths = @('platform-tools','emulator','cmdline-tools/latest/bin') | ForEach-Object { Join-Path $selectedSdk $_ }
$androidPaths = @((Join-Path $selectedJbr 'bin')) + $androidPaths
$env:PATH = (($androidPaths + ([string]$env:PATH).Split([IO.Path]::PathSeparator)) | Select-Object -Unique) -join [IO.Path]::PathSeparator
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
