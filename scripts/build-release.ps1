[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'android-env.ps1')

$required = @(
    'VOLUME_MAPPER_KEYSTORE',
    'VOLUME_MAPPER_STORE_PASSWORD',
    'VOLUME_MAPPER_KEY_ALIAS',
    'VOLUME_MAPPER_KEY_PASSWORD'
)
foreach ($name in $required) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        throw "缺少进程环境变量：$name。详见 docs/RELEASE.md。"
    }
}

& (Join-Path $script:ProjectRoot 'gradlew.bat') `
    :app:testDebugUnitTest `
    :app:lintRelease `
    :app:assembleRelease `
    :app:bundleRelease
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
