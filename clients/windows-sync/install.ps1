param(
  [switch]$Run,
  [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'CloudNestSync\development'),
  [string]$ShortcutDirectory = [Environment]::GetFolderPath('Programs')
)
$ErrorActionPreference = 'Stop'
function Get-SourceDigest([string]$Path) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($Path))).Replace('-','').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
$names = @('cloudnest_sync.py','desktop.py','sync_engine.py','transport.py','credentials.py','README.md','uninstall.ps1')
function Assert-PlainPath([string]$Path) {
  $current = [IO.Path]::GetFullPath($Path)
  while ($current) {
    if (Test-Path -LiteralPath $current) {
      $item = Get-Item -LiteralPath $current -Force
      if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points are not supported for installation.' }
    }
    $parent = Split-Path -Parent $current
    if ($parent -eq $current) { break }
    $current = $parent
  }
}
$applicationPath = [IO.Path]::GetFullPath($InstallDirectory)
$shortcutPath = [IO.Path]::GetFullPath((Join-Path $ShortcutDirectory 'CloudNest Sync Development.lnk'))
Assert-PlainPath $applicationPath
Assert-PlainPath $shortcutPath
foreach ($name in $names + @('install.ps1')) {
  $source = Join-Path $PSScriptRoot $name
  Assert-PlainPath $source
  if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing package file: $name" }
}
$manifestPath = Join-Path $PSScriptRoot 'manifest.json'
if (Test-Path -LiteralPath $manifestPath) {
  $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $expectedNames = $names + @('install.ps1')
  if ($manifest.schema -ne 1 -or $manifest.development -ne $true -or
      @(Compare-Object $expectedNames @($manifest.sha256.PSObject.Properties.Name)).Count) { throw 'Invalid package manifest.' }
  foreach ($name in $expectedNames) {
    $actual = (Get-SourceDigest (Join-Path $PSScriptRoot $name))
    if ($actual -cne [string]$manifest.sha256.$name) { throw "Package checksum mismatch: $name" }
  }
}
$statePath = Join-Path $applicationPath 'install-state.json'
Assert-PlainPath $statePath
if (Test-Path -LiteralPath $applicationPath) {
  if (-not (Test-Path -LiteralPath $applicationPath -PathType Container)) { throw 'Installation path is not a directory.' }
  if (Test-Path -LiteralPath $statePath) {
    $previous = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($previous.app -ne 'CloudNestSyncDevelopment' -or $previous.schema -ne 1 -or
        $previous.directory -ne $applicationPath) { throw 'Unrecognized existing installation.' }
    foreach ($name in $names) {
      $destination = Join-Path $applicationPath $name
      Assert-PlainPath $destination
      if (Test-Path -LiteralPath $destination) {
        $hash = (Get-SourceDigest $destination)
        if (-not $previous.files.$name -or $hash -cne [string]$previous.files.$name) {
          throw "Installed file was modified; preserve it before upgrading: $name"
        }
      }
    }
  } elseif (@(Get-ChildItem -LiteralPath $applicationPath -Force).Count) {
    throw 'Installation directory is not empty and has no ownership marker. Choose another directory.'
  }
}
$shell = New-Object -ComObject WScript.Shell
$desktopArgument = '"' + (Join-Path $applicationPath 'desktop.py') + '"'
if (Test-Path -LiteralPath $shortcutPath) {
  $existing = $shell.CreateShortcut($shortcutPath)
  if ($existing.Arguments -ne $desktopArgument -or -not (Test-Path -LiteralPath $statePath)) {
    throw 'The existing shortcut is not owned by this installation.'
  }
}
$pythonCommand = Get-Command python.exe -ErrorAction Stop
$pythonPath = (& $pythonCommand.Source -c 'import sys; print(sys.executable)').Trim()
if ($LASTEXITCODE) { throw 'Python 3.11+ is required.' }
& $pythonPath -c 'import sys, tkinter; assert sys.version_info >= (3,11)'
if ($LASTEXITCODE) { throw 'Install Python 3.11+ with Tcl/Tk support.' }
$pythonWindowPath = Join-Path (Split-Path -Parent $pythonPath) 'pythonw.exe'
if (-not (Test-Path -LiteralPath $pythonWindowPath -PathType Leaf)) { throw 'pythonw.exe is required for the desktop window.' }
New-Item -ItemType Directory -Path $applicationPath -Force | Out-Null
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($shortcutPath)) -Force | Out-Null
$hashes = [ordered]@{}
foreach ($name in $names) {
  $destination = Join-Path $applicationPath $name
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $destination -Force
  $hashes[$name] = (Get-SourceDigest $destination)
}
$state = [ordered]@{ app='CloudNestSyncDevelopment'; schema=1; directory=$applicationPath; shortcut=$shortcutPath; files=$hashes }
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding UTF8
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $pythonWindowPath
$shortcut.Arguments = $desktopArgument
$shortcut.WorkingDirectory = $applicationPath
$shortcut.Description = 'CloudNest sync development preview; independent backups required.'
$shortcut.Save()
Write-Output "Installed current-user application: $applicationPath"
Write-Output "Start menu shortcut: $shortcutPath"
if ($Run) { Start-Process -FilePath $pythonWindowPath -ArgumentList @($desktopArgument) -WindowStyle Hidden }
