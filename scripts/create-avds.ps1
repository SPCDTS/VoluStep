[CmdletBinding()]
param(
    [switch]$Force
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$avdManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\avdmanager.bat'
if (-not (Test-Path $avdManager)) {
    throw '缺少 avdmanager。请使用项目隔离 SDK，或在 SDK Manager 安装 Command-line Tools。'
}

$definitions = @(
    @{ Name = 'VolumeMapper_API_28'; Package = 'system-images;android-28;google_apis;x86_64'; Device = 'pixel_2' },
    @{ Name = 'VolumeMapper_API_37'; Package = 'system-images;android-37.0;google_apis;x86_64'; Device = 'pixel_7' }
)

$existing = & $avdManager list avd -c
foreach ($definition in $definitions) {
    if ($existing -contains $definition.Name) {
        if (-not $Force) {
            Write-Host "已存在 $($definition.Name)，跳过"
            continue
        }
        & $avdManager delete avd --name $definition.Name
        if ($LASTEXITCODE -ne 0) { throw "删除旧 AVD 失败：$($definition.Name)" }
    }

    'no' | & $avdManager create avd `
        --name $definition.Name `
        --package $definition.Package `
        --device $definition.Device `
        --force
    if ($LASTEXITCODE -ne 0) { throw "创建 AVD 失败：$($definition.Name)" }
}
