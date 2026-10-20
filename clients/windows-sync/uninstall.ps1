param([string]$ShortcutDirectory = [Environment]::GetFolderPath('Programs'))
$ErrorActionPreference = 'Stop'
function Get-SourceDigest([string]$Path) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($Path))).Replace('-','').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
$applicationPath = [IO.Path]::GetFullPath($PSScriptRoot)
function Assert-PlainPath([string]$Path) {
  $current = [IO.Path]::GetFullPath($Path)
  while ($current) {
    if (Test-Path -LiteralPath $current) {
      $item = Get-Item -LiteralPath $current -Force
      if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing to uninstall through a reparse point.' }
    }
    $parent = Split-Path -Parent $current
    if ($parent -eq $current) { break }
    $current = $parent
  }
}
Assert-PlainPath $applicationPath
$statePath = Join-Path $applicationPath 'install-state.json'
Assert-PlainPath $statePath
if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No installation ownership marker found.' }
$state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
$names = @('cloudnest_sync.py','desktop.py','sync_engine.py','transport.py','credentials.py','README.md','uninstall.ps1')
$shortcutPath = [IO.Path]::GetFullPath((Join-Path $ShortcutDirectory 'CloudNest Sync Development.lnk'))
if ($state.app -ne 'CloudNestSyncDevelopment' -or $state.schema -ne 1 -or
    $state.directory -ne $applicationPath -or $state.shortcut -ne $shortcutPath -or
    @(Compare-Object $names @($state.files.PSObject.Properties.Name)).Count) { throw 'Invalid installation ownership marker.' }
foreach ($name in $names) {
  if ([string]$state.files.$name -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid installed file digest.' }
  Assert-PlainPath (Join-Path $applicationPath $name)
}
Assert-PlainPath $shortcutPath
if (Test-Path -LiteralPath $shortcutPath) {
  $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
  if ($shortcut.Arguments -eq ('"' + (Join-Path $applicationPath 'desktop.py') + '"')) {
    Remove-Item -LiteralPath $shortcutPath -Force
  } else { Write-Warning 'Shortcut was changed; preserving it.' }
}
$preserved = $false
foreach ($name in $names) {
  if ($name -eq 'uninstall.ps1' -and $preserved) { continue }
  $file = Join-Path $applicationPath $name
  Assert-PlainPath $file
  if (Test-Path -LiteralPath $file -PathType Leaf) {
    $hash = (Get-SourceDigest $file)
    if ($hash -ceq [string]$state.files.$name) { Remove-Item -LiteralPath $file -Force }
    else { $preserved = $true; Write-Warning "Modified file preserved: $name" }
  } elseif (Test-Path -LiteralPath $file) { $preserved = $true }
}
if (-not $preserved) { Remove-Item -LiteralPath $statePath -Force }
# No recursive deletion: unknown files, caches and all sync directories remain.
if (-not @(Get-ChildItem -LiteralPath $applicationPath -Force).Count) {
  Remove-Item -LiteralPath $applicationPath -Force
}
Write-Output 'Removed unchanged installed files. Sync data and stored device credentials were not touched.'
Write-Output 'To revoke a device, use Account and security > Sync devices, or log out in the client before uninstalling.'
