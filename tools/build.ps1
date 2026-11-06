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
    # Four places say which version this is, and an installer whose title disagrees with its
    # About line is the kind of thing nobody notices until a bug report quotes the wrong one.
    $versions = @{
        'build.gradle.kts' = [regex]::Match((Get-Content -Raw build.gradle.kts), 'version = "([^"]+)"').Groups[1].Value
        'desktop/build.gradle.kts' = [regex]::Match((Get-Content -Raw desktop/build.gradle.kts), 'packageVersion = "([^"]+)"').Groups[1].Value
        'installer/Yumi.iss' = [regex]::Match((Get-Content -Raw installer/Yumi.iss), '#define AppVersion "([^"]+)"').Groups[1].Value
        'BuildInfo.kt' = [regex]::Match((Get-Content -Raw desktop/src/main/kotlin/com/yumi/desktop/BuildInfo.kt), 'AppVersion = "([^"]+)"').Groups[1].Value
    }
    if (@($versions.Values | Sort-Object -Unique).Count -ne 1) {
        throw ('Version mismatch: ' + (($versions.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '))
    }
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
