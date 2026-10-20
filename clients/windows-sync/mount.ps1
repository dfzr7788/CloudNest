param(
  [Parameter(Mandatory=$true)][string]$Server,
  [ValidatePattern('^[D-Zd-z]$')][string]$Drive='N',
  [switch]$Unmount
)
$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows WebDAV redirector is required.' }
$uri = [Uri]$Server
if ($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
  throw 'Use the HTTPS server origin, for example https://cloud.example.com/.'
}
$driveName = $Drive.ToUpperInvariant()
if ($Unmount) {
  Remove-PSDrive -Name $driveName -Force -ErrorAction Stop
  Write-Output 'Drive disconnected. Check open applications for unsaved changes.'
  exit
}
if (Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue) { throw 'The drive letter is already in use.' }
$service=Get-Service WebClient -ErrorAction Stop
if ($service.Status -ne 'Running') { Start-Service WebClient -ErrorAction Stop }
$credential=Get-Credential -Message 'Enter your CloudNest username and a dedicated WebDAV application password.'
if (-not $credential) { throw 'Mount cancelled.' }
$davRoot='\\' + $uri.DnsSafeHost + '@SSL@' + $uri.Port + '\DavWWWRoot\dav'
New-PSDrive -Name $driveName -PSProvider FileSystem -Root $davRoot -Credential $credential -Persist -Scope Global | Out-Null
Write-Output "CloudNest mounted as ${driveName}: using Windows WebDAV. Offline access and file size limits follow Windows WebClient settings."
