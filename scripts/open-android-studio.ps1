[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'android-env.ps1')

$portableStudio = Join-Path $script:ProjectRoot '.toolchains\android-studio\bin\studio64.exe'
$installedStudio = 'C:\Program Files\Android\Android Studio\bin\studio64.exe'
$studio = if (Test-Path $portableStudio) {
    $portableStudio
} elseif (Test-Path $installedStudio) {
    $installedStudio
} else {
    throw '未找到 Android Studio。请检查项目 .toolchains 或系统安装目录。'
}

Start-Process -FilePath $studio -ArgumentList @($script:ProjectRoot)
