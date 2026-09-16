[CmdletBinding()]
param(
    [string]$KeyDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/volustep-signing')
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-env.ps1')
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw '此脚本使用 Windows ACL 保护密钥，请在 Windows 上创建。'
}
$keyPath = Join-Path $KeyDirectory 'volustep-release.p12'
$passwordPath = Join-Path $KeyDirectory 'password.txt'
if (Test-Path -LiteralPath $KeyDirectory) {
    throw "签名目录已存在，拒绝覆盖或更换发布密钥：$KeyDirectory"
}
New-Item -ItemType Directory -Path $KeyDirectory | Out-Null
# 私钥和可移植的恢复密码只允许当前 Windows 用户访问；不进入仓库。
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = Get-Acl -LiteralPath $KeyDirectory
$acl.SetAccessRuleProtection($true, $false)
$rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
)
$acl.SetAccessRule($rule)
Set-Acl -LiteralPath $KeyDirectory -AclObject $acl
$random = [byte[]]::new(32)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($random) } finally { $rng.Dispose() }
$env:VOLUSTEP_STORE_PASSWORD = [Convert]::ToBase64String($random)
try {
    [IO.File]::WriteAllText($passwordPath, $env:VOLUSTEP_STORE_PASSWORD, [Text.UTF8Encoding]::new($false))
    & keytool -genkeypair -keystore $keyPath -storetype PKCS12 -alias volustep `
        -keyalg RSA -keysize 3072 -validity 10950 -dname 'CN=VoluStep' `
        '-storepass:env' VOLUSTEP_STORE_PASSWORD '-keypass:env' VOLUSTEP_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw '创建密钥失败。请检查签名目录中的文件，脚本不会自动覆盖。' }
    Write-Host "发布密钥已创建：$keyPath"
    Write-Host "恢复密码：$passwordPath（未输出密码内容）"
    Write-Host '请将整个签名目录备份到加密存储；丢失密钥后将无法给现有安装提供同签名更新。'
} finally {
    Remove-Item Env:VOLUSTEP_STORE_PASSWORD -ErrorAction SilentlyContinue
    [Array]::Clear($random, 0, $random.Length)
}
