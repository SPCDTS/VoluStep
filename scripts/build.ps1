[CmdletBinding()]
param(
    [switch]$Release,
    [switch]$Clean
)

. (Join-Path $PSScriptRoot 'android-env.ps1')

$gradle = Join-Path $script:ProjectRoot 'gradlew.bat'
$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += ':app:testDebugUnitTest'
$tasks += ':app:compileDebugAndroidTestKotlin'
$tasks += ':app:lintDebug'
$tasks += ':app:assembleDebug'
if ($Release) {
    $tasks += ':app:assembleRelease'
}

& $gradle --stacktrace @tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
