param([Parameter(Mandatory)][string]$Archive,[Parameter(Mandatory)][string]$Target)
$ErrorActionPreference='Stop'
$archivePath=(Resolve-Path -LiteralPath $Archive).Path
$targetPath=[System.IO.Path]::GetFullPath($Target)
if (Test-Path -LiteralPath $targetPath) { throw 'Restore into a new directory; existing directories are never overwritten.' }
New-Item -ItemType Directory -Path $targetPath | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory($archivePath,$targetPath)
if (-not(Test-Path -LiteralPath (Join-Path $targetPath 'cloudnest.mv.db'))) { throw 'Archive does not contain the expected H2 database.' }
Write-Output "Restored into $targetPath. Set DATA_DIR and DATABASE_URL to this directory before starting."
