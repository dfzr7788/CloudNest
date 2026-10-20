param([switch]$Run)
$ErrorActionPreference = 'Stop'
$pythonCommand = Get-Command python.exe -ErrorAction Stop
$pythonPath = (& $pythonCommand.Source -c 'import sys; print(sys.executable)').Trim()
if ($LASTEXITCODE) { throw 'Python 3.11+ is required.' }
& $pythonPath -c 'import sys, tkinter; assert sys.version_info >= (3,11)'
if ($LASTEXITCODE) { throw 'Install Python 3.11+ with Tcl/Tk support.' }
$pythonWindowPath = Join-Path (Split-Path -Parent $pythonPath) 'pythonw.exe'
if (-not (Test-Path -LiteralPath $pythonWindowPath)) { throw 'pythonw.exe is required for the desktop window.' }
$applicationPath = Join-Path $env:LOCALAPPDATA 'CloudNestSync\development'
New-Item -ItemType Directory -Path $applicationPath -Force | Out-Null
foreach ($name in @('cloudnest_sync.py','desktop.py','sync_engine.py','transport.py','credentials.py','README.md')) {
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $applicationPath $name) -Force
}
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Programs')) 'CloudNest Sync Development.lnk'
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath = $pythonWindowPath
$shortcut.Arguments = '"' + (Join-Path $applicationPath 'desktop.py') + '"'
$shortcut.WorkingDirectory = $applicationPath
$shortcut.Description = 'CloudNest sync development preview; independent backups required.'
$shortcut.Save()
Write-Output "Installed current-user application: $applicationPath"
Write-Output "Start menu shortcut: $shortcutPath"
if ($Run) {
  Start-Process -FilePath $pythonWindowPath -ArgumentList @('"' + (Join-Path $applicationPath 'desktop.py') + '"') -WindowStyle Hidden
}
