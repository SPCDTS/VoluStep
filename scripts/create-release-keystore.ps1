[CmdletBinding()]
param(
    [string]$Alias = 'volume-mapper-release',
    [int]$ValidityDays = 10000
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$keystoreDirectory = Join-Path $script:ProjectRoot 'keystore'
$keystorePath = Join-Path $keystoreDirectory 'volume-mapper-release.jks'
if (Test-Path $keystorePath) {
    throw "签名库已存在，不会覆盖：$keystorePath"
}
New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null

$securePassword = Read-Host '请输入新的签名库密码（至少 6 位）' -AsSecureString
$credential = [PSCredential]::new('unused', $securePassword)
$plainPassword = $credential.GetNetworkCredential().Password
if ($plainPassword.Length -lt 6) { throw '密码至少需要 6 位。' }

$env:VOLUME_MAPPER_TEMP_KEY_PASSWORD = $plainPassword
try {
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    & $keytool -genkeypair `
        -keystore $keystorePath `
        -storetype PKCS12 `
        -storepass:env VOLUME_MAPPER_TEMP_KEY_PASSWORD `
        -keypass:env VOLUME_MAPPER_TEMP_KEY_PASSWORD `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity $ValidityDays `
        -dname 'CN=Volume Mapper, OU=Mobile, O=Local Developer, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool 创建签名库失败。' }
} finally {
    Remove-Item Env:VOLUME_MAPPER_TEMP_KEY_PASSWORD -ErrorAction SilentlyContinue
    $plainPassword = $null
}

Write-Host "已创建：$keystorePath"
Write-Host '该文件已被 .gitignore 排除。请立即离线备份；丢失后无法更新已发布应用。'
