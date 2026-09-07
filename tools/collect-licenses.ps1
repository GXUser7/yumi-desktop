param([string]$GoExe = 'go')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot 'runtime/licenses'
# Emptied rather than overwritten. Files copied out of the Go module cache keep its read-only
# attribute, so the second run of this script met a destination it was not allowed to replace and
# every build after the first one failed here.
if (Test-Path -LiteralPath $destination) {
    Get-ChildItem -LiteralPath $destination -File -Recurse | ForEach-Object { $_.IsReadOnly = $false }
    Remove-Item -LiteralPath $destination -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$modules = & $GoExe -C (Join-Path $projectRoot 'service') list -m -f '{{.Path}}|{{.Version}}|{{.Dir}}' all
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Go dependency licenses.' }
$inventory = @()
foreach ($module in $modules) {
    $parts = $module -split '\|'
    if ($parts.Count -lt 3 -or -not $parts[2]) { continue }
    $inventory += $parts[0] + ' ' + $parts[1]
    $name = $parts[0] -replace '[^A-Za-z0-9.-]','_'
    Get-ChildItem -LiteralPath $parts[2] -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE)' } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $destination ($name + '-' + $_.Name))
    }
}
$inventory | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $destination 'Go-dependencies.txt')
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY.md') -Destination $destination
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'licenses') -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $destination }
# Cleared on the way out as well, so the folder can be replaced without elevation next time.
Get-ChildItem -LiteralPath $destination -File | ForEach-Object { $_.IsReadOnly = $false }
