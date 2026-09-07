$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$downloads = Join-Path $projectRoot '.local/downloads'
$toolRoot = Join-Path $projectRoot '.local/tools'
New-Item -ItemType Directory -Force -Path $downloads,$toolRoot,(Join-Path $projectRoot 'runtime/licenses') | Out-Null
function Get-VerifiedFile([string]$Url,[string]$Destination,[string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -ne $Sha256) { throw "Checksum mismatch: $Destination" }
}
$wintun = Join-Path $downloads 'wintun-0.14.1.zip'
Get-VerifiedFile 'https://www.wintun.net/builds/wintun-0.14.1.zip' $wintun '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51'
Expand-Archive -LiteralPath $wintun -DestinationPath (Join-Path $toolRoot 'wintun') -Force
Copy-Item -LiteralPath (Join-Path $toolRoot 'wintun/wintun/bin/amd64/wintun.dll') -Destination (Join-Path $projectRoot 'runtime/wintun.dll')
Copy-Item -LiteralPath (Join-Path $toolRoot 'wintun/wintun/LICENSE.txt') -Destination (Join-Path $projectRoot 'runtime/licenses/Wintun.txt')
$inno = Join-Path $downloads 'innosetup-6.5.3.exe'
Get-VerifiedFile 'https://github.com/jrsoftware/issrc/releases/download/is-6_5_3/innosetup-6.5.3.exe' $inno '9345ee029faa0b7aed0818c3d5b227699ef9a496cce79e20c19eb9d6ef2e2c2d'
if (-not (Test-Path -LiteralPath (Join-Path $toolRoot 'inno/ISCC.exe'))) {
    $arguments = '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CURRENTUSER /NOICONS /DIR="' + (Join-Path $toolRoot 'inno') + '"'
    $process = Start-Process -FilePath $inno -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw 'Inno Setup tool installation failed.' }
}
Write-Output 'Wintun and Inno Setup are ready. No VPN service was installed or started.'
