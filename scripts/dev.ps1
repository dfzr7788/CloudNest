param([ValidateSet('server','web','test','build')][string]$Action='server')
$ErrorActionPreference='Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if ($Action -eq 'web') {
  Set-Location cloudnest-web
  if (-not(Test-Path node_modules)) { npm ci; if ($LASTEXITCODE) { exit $LASTEXITCODE } }
  npm run dev
  exit $LASTEXITCODE
}
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($mavenCommand) { $mavenPath=$mavenCommand.Source }
elseif (Test-Path 'D:\maven\apache-maven-3.9.16\bin\mvn.cmd') { $mavenPath='D:\maven\apache-maven-3.9.16\bin\mvn.cmd' }
elseif (Test-Path 'D:\maven\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd') { $mavenPath='D:\maven\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd' }
else { throw 'Install Maven 3.9+ and add mvn to PATH.' }
$mavenOptions=@('-B',"-Dmaven.repo.local=$projectRoot/.tools/m2",'-f','cloudnest-server/pom.xml')
switch ($Action) {
  'server' { & $mavenPath @mavenOptions spring-boot:run }
  'test' { & $mavenPath @mavenOptions test; if ($LASTEXITCODE) { exit $LASTEXITCODE }; Set-Location cloudnest-web; npm run build }
  'build' { & $mavenPath @mavenOptions package; if ($LASTEXITCODE) { exit $LASTEXITCODE }; Set-Location cloudnest-web; npm ci; if ($LASTEXITCODE) { exit $LASTEXITCODE }; npm run build }
}
exit $LASTEXITCODE
