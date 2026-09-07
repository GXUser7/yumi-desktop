param(
    [string]$GoExe = 'go',
    [string]$InnoCompiler = '',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
    $env:GOTOOLCHAIN = 'local'
    New-Item -ItemType Directory -Force -Path runtime/licenses,dist | Out-Null
    if (-not $SkipTests) {
        & ./gradlew.bat :logic:test :platform:test :desktop:test --rerun-tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'JVM tests failed.' }
        & $GoExe -C service test ./... -count=1
        if ($LASTEXITCODE -ne 0) { throw 'Service tests failed.' }
    }
    & $GoExe -C service build -trimpath -ldflags '-s -w' -o ../runtime/yumi-service.exe .
    if ($LASTEXITCODE -ne 0) { throw 'Service build failed.' }
    & ./gradlew.bat :desktop:createDistributable --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed.' }
    if (-not (Test-Path runtime/wintun.dll)) { throw 'Run tools/bootstrap.ps1 to obtain the verified Wintun runtime.' }
    & $PSScriptRoot/collect-licenses.ps1 -GoExe $GoExe
    if (-not $InnoCompiler) { $InnoCompiler = Join-Path $projectRoot '.local/tools/inno/ISCC.exe' }
    & $InnoCompiler installer/Yumi.iss
    if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
    Get-FileHash dist/*setup.exe -Algorithm SHA256 | Format-List
} finally { Pop-Location }
