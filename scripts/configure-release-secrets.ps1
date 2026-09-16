[CmdletBinding()]
param(
    [string]$Repository = 'SPCDTS/VoluStep',
    [string]$KeyDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/volustep-signing')
)

$ErrorActionPreference = 'Stop'
# gh 在本机使用 GitHub 公钥加密 secret；密码不出现在参数或日志中。
$values = @{
    VOLUSTEP_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $KeyDirectory 'volustep-release.p12')))
    VOLUSTEP_STORE_PASSWORD = [IO.File]::ReadAllText((Join-Path $KeyDirectory 'password.txt')).Trim()
    VOLUSTEP_KEY_ALIAS = 'volustep'
}
$values.VOLUSTEP_KEY_PASSWORD = $values.VOLUSTEP_STORE_PASSWORD
try {
    foreach ($name in $values.Keys) {
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = (Get-Command gh -ErrorAction Stop).Source
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $true
        # 参数只有变量名与仓库名，密码只通过 stdin 传递，且不附加换行。
        if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw '仓库名无效。' }
        $start.Arguments = "secret set $name --repo $Repository"
        $process = [Diagnostics.Process]::Start($start)
        try {
            $process.StandardInput.Write($values[$name])
            $process.StandardInput.Close()
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw "GitHub Secret 配置失败：$name" }
        } finally { $process.Dispose() }
        Write-Host "已配置 $name"
    }
} finally { $values.Clear() }
