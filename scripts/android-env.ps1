[CmdletBinding()]
param()

$script:ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localSdk = Join-Path $script:ProjectRoot '.toolchains\android-sdk'
$portableJbr = Join-Path $script:ProjectRoot '.toolchains\android-studio\jbr'
$installedJbr = 'C:\Program Files\Android\Android Studio\jbr'
$userSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

$selectedJbr = if (Test-Path (Join-Path $portableJbr 'bin\java.exe')) {
    $portableJbr
} elseif (Test-Path (Join-Path $installedJbr 'bin\java.exe')) {
    $installedJbr
} else {
    throw '未找到 Android Studio JBR。请先完成 README 中的环境安装。'
}

$selectedSdk = if (Test-Path (Join-Path $localSdk 'platform-tools')) {
    $localSdk
} elseif (Test-Path (Join-Path $userSdk 'platform-tools')) {
    $userSdk
} else {
    throw '未找到 Android SDK Platform Tools。请先完成 README 中的环境安装。'
}

$env:JAVA_HOME = $selectedJbr
$env:ANDROID_HOME = $selectedSdk
$env:ANDROID_SDK_ROOT = $selectedSdk
$androidPaths = @(
    (Join-Path $selectedJbr 'bin'),
    (Join-Path $selectedSdk 'platform-tools'),
    (Join-Path $selectedSdk 'emulator'),
    (Join-Path $selectedSdk 'cmdline-tools\latest\bin')
)
$env:Path = (($androidPaths + $env:Path.Split([IO.Path]::PathSeparator)) |
    Select-Object -Unique) -join [IO.Path]::PathSeparator

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
