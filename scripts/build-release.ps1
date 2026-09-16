[CmdletBinding()]
param(
    [string]$KeyDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/volustep-signing'),
    [string[]]$GradleArguments = @()
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-env.ps1')
$env:VOLUSTEP_KEYSTORE = Join-Path $KeyDirectory 'volustep-release.p12'
$env:VOLUSTEP_KEY_ALIAS = 'volustep'
try {
    $env:VOLUSTEP_STORE_PASSWORD = [IO.File]::ReadAllText((Join-Path $KeyDirectory 'password.txt')).Trim()
    $env:VOLUSTEP_KEY_PASSWORD = $env:VOLUSTEP_STORE_PASSWORD
    & (Join-Path $script:ProjectRoot 'gradlew.bat') --no-daemon --no-configuration-cache `
        @GradleArguments -PrequireReleaseSigning=true :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw '正式版构建未通过。' }
    $apk = Join-Path $script:ProjectRoot 'app/build/outputs/apk/release/app-release.apk'
    $apksigner = Join-Path $env:ANDROID_HOME 'build-tools/37.0.0/apksigner.bat'
    & $apksigner verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$apk.sha256", "$hash  app-release.apk`n", [Text.UTF8Encoding]::new($false))
    Write-Host "正式安装包：$apk"
} finally {
    foreach ($name in @('VOLUSTEP_KEYSTORE','VOLUSTEP_KEY_ALIAS','VOLUSTEP_STORE_PASSWORD','VOLUSTEP_KEY_PASSWORD')) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
