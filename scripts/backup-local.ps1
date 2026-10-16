param([string]$DataDir='cloudnest-server/.data',[string]$Destination='backups',[int]$Port=8080)
$ErrorActionPreference='Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
# File-based H2 must be offline. MySQL backup procedure is documented separately.
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'Stop the server before taking an offline backup.' }
$sourcePath = (Resolve-Path -LiteralPath $DataDir).Path
if (-not(Test-Path -LiteralPath (Join-Path $sourcePath 'cloudnest.mv.db'))) { throw 'This script expects a local H2 data directory.' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$archivePath = Join-Path (Resolve-Path -LiteralPath $Destination).Path ('cloudnest-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.zip')
$databasePath = Join-Path $sourcePath 'cloudnest.mv.db'
# Holding a read-only shared lease prevents a writer from opening this DB during the snapshot.
$databaseLease = [System.IO.File]::Open($databasePath,[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
try { [System.IO.Compression.ZipFile]::CreateFromDirectory($sourcePath,$archivePath,[System.IO.Compression.CompressionLevel]::Fastest,$false) }
finally { $databaseLease.Dispose() }
Get-FileHash -LiteralPath $archivePath -Algorithm SHA256 | Format-List
Write-Output "Backup created: $archivePath"
