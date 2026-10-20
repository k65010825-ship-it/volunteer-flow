param([switch]$Child, [switch]$PowerShell7Check)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $Child) {
  if ($env:OS -ne 'Windows_NT') { throw 'Actual Windows PowerShell 5.1 is required' }
  & "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -Version 5.1 -NoProfile -NonInteractive -File $PSCommandPath -Child
  if ($LASTEXITCODE -ne 0) { throw 'PowerShell 5.1 compatibility suite failed' }
  $pwsh = @(Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue)
  if ($pwsh.Count) {
    & $pwsh[0].Source -NoProfile -NonInteractive -File $PSCommandPath -Child -PowerShell7Check
    if ($LASTEXITCODE -ne 0) { throw 'PowerShell 7 compatibility cross-check failed' }
  }
  return
}
if ($PowerShell7Check) {
  if ($PSVersionTable.PSVersion.Major -ne 7) { throw 'Expected PowerShell 7 cross-check' }
} elseif ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1) { throw 'Expected actual PowerShell 5.1' }
$runner = Join-Path $PSScriptRoot 'run-stage5.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($runner, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Runner syntax failed' }
$parser = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-Stage5Environment' }, $true)
if ($null -eq $parser) { throw 'Missing compatible strict dotenv parser' }
Invoke-Expression $parser.Extent.Text
$assertions = 0
function Assert-True([bool]$Condition, [string]$Category) {
  if (-not $Condition) { throw $Category }
  $script:assertions++
}
$root = Join-Path ([IO.Path]::GetTempPath()) ('stage5-ps51-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$fixtureEnvironment = @'
DB_HOST=localhost
DB_PORT=3306
DB_NAME=fixture
DB_USERNAME=fixture
DB_PASSWORD=private-marker==
JWT_SECRET=abc=def==
REDIS_HOST=localhost
REDIS_PORT=6379
RATE_LIMIT_KEY_SECRET=private-marker-rate
SPRING_FLYWAY_ENABLED=false
STAGE5_MYSQL_CONTAINER=mysql-fixture
STAGE5_REDIS_CONTAINER=redis-fixture
STAGE5_MAINTENANCE_APPROVED=true
'@
try {
  $envPath = Join-Path $root 'fixture.env'
  foreach ($bom in @($false, $true)) {
    $encoding = [Text.UTF8Encoding]::new($bom, $true)
    [IO.File]::WriteAllText($envPath, ($fixtureEnvironment -replace "`r?`n", "`r`n"), $encoding)
    $parsed = Read-Stage5Environment $envPath
    Assert-True ($parsed['DB_PASSWORD'] -ceq 'private-marker==') 'First literal equals was not preserved'
    Assert-True ($parsed['JWT_SECRET'] -ceq 'abc=def==') 'Additional equals were not preserved'
  }
  [IO.File]::WriteAllText($envPath, "# fixture comment`nREDIS_PASSWORD=`nDB_PASSWORD=private-marker==`n", $utf8)
  $parsed = Read-Stage5Environment $envPath
  Assert-True ($parsed['REDIS_PASSWORD'] -ceq '') 'Optional empty value was not preserved'
  Assert-True ($parsed.Count -eq 2) 'LF/comment parsing changed'
  foreach ($invalid in @("DB_PASSWORD=private-marker`nDB_PASSWORD=duplicate", 'DB_PASSWORD=private-marker bad',
      'DB_PASSWORD=private-marker#bad', 'DB_PASSWORD=private-marker$bad', 'DB_PASSWORD=private-marker\bad',
      'DB_PASSWORD=private-marker"bad', 'DB_PASSWORD=private-marker''bad', 'DB_PASSWORD=change-me-private-marker',
      'UNKNOWN_KEY=private-marker', 'no-equals-private-marker', "DB_PASSWORD=private-marker$([char]0)")) {
    [IO.File]::WriteAllText($envPath, $invalid, $utf8)
    $rejected = $false
    try { Read-Stage5Environment $envPath | Out-Null } catch {
      $rejected = $true
      Assert-True ($_.Exception.Message -notmatch 'private-marker') 'Parser disclosed a secret'
    }
    Assert-True $rejected 'Unsafe dotenv input was accepted'
  }
  foreach ($bytes in @([byte[]](255,254,68,0,66,0), [byte[]](68,66,61,255))) {
    [IO.File]::WriteAllBytes($envPath, $bytes)
    $rejected = $false
    try { Read-Stage5Environment $envPath | Out-Null } catch { $rejected = $true }
    Assert-True $rejected 'Non UTF-8 input was accepted'
  }
  # A fresh LOCAL Git repository exercises clean HEAD/archive gates without modifying the checkout.
  $repo = Join-Path $root 'repo'
  $stage = Join-Path $repo 'deploy/stage5'
  New-Item -ItemType Directory -Path $stage -Force | Out-Null
  New-Item -ItemType Directory -Path (Join-Path $repo 'deploy/lib') | Out-Null
  foreach ($name in @('run-stage5.ps1','archive-driver-hash.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $stage $name)
  }
  $fixtureRunner = Join-Path $stage 'run-stage5.ps1'
  # Replace only external network/key-removal boundaries with fail-closed doubles.
  # All validation, resolver, Git, archive, hash, cleanup and JSON code remains real.
  $fixtureSource = [IO.File]::ReadAllText($fixtureRunner)
  $offlineSource = [regex]::Replace($fixtureSource,
    '(?m)^\s*& \$(?:ssh|scp)\b[^\r\n]*|^\s*& \$python [^\r\n]*remove-temporary-key[^\r\n]*',
    '  & { $global:stage5OfflineNetworkCalls++; throw ''Network process forbidden in offline tests'' }')
  Assert-True ($offlineSource -cne $fixtureSource) 'Network boundary doubles were not installed'
  [IO.File]::WriteAllText($fixtureRunner, $offlineSource, $utf8)
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../lib/native-tools.psm1') -Destination (Join-Path $repo 'deploy/lib/native-tools.psm1')
  [IO.File]::WriteAllText((Join-Path $stage 'acceptance.py'), "print('fixture-driver')`n", $utf8)
  Import-Module (Join-Path $repo 'deploy/lib/native-tools.psm1') -Force
  $git = Resolve-ApprovedApplication -Name git
  & $git -C $repo init -q -b feat/stage-5
  if ($LASTEXITCODE) { throw 'Fixture Git initialization failed' }
  & $git -C $repo config core.autocrlf false
  function Commit-Fixture {
    & $git -C $repo add deploy
    & $git -C $repo -c user.name=Stage5Offline -c user.email=stage5@invalid -c commit.gpgsign=false commit -qm fixture
    if ($LASTEXITCODE) { throw 'Fixture commit failed' }
  }
  Commit-Fixture
  $identity = Join-Path $root 'identity'
  [IO.File]::WriteAllText($identity, "-----BEGIN OPENSSH PRIVATE KEY-----`nZmFrZQ==`n-----END OPENSSH PRIVATE KEY-----`n", $utf8)
  [IO.File]::WriteAllText(($identity + '.pub'), 'ssh-ed25519 ZmFrZQ== volunteerflow-stage4d-11111111-1111-1111-1111-111111111111', $utf8)
  $temporaryRoot = Join-Path $root 'temp'
  New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
  $oldTemp = $env:TEMP; $oldTmp = $env:TMP
  $env:TEMP = $temporaryRoot; $env:TMP = $temporaryRoot
  $global:stage5OfflineNetworkCalls = 0
  function Invoke-Preflight([hashtable]$Overrides = @{}, [string]$Failure = '') {
    $arguments = @{VmHost='vm.invalid';IdentityFile=$identity;ComposeEnvFile=$envPath;AuthorizeLiveRun=$true;
      LocalPreflightOnly=$true;RunId='11111111-1111-1111-1111-111111111111'}
    foreach ($key in $Overrides.Keys) { $arguments[$key] = $Overrides[$key] }
    $failed = $false; $captured = ''
    try { $captured = (& $fixtureRunner @arguments 2>&1 | Out-String).Trim() } catch {
      $failed = $true; $captured = $_.Exception.Message
    }
    Assert-True ($captured -notmatch 'private-marker|abc=def|ZmFrZQ|DB_PASSWORD=') 'Preflight disclosed secrets'
    if ($Failure) {
      Assert-True ($failed -and $captured -match $Failure) ("Expected safe local failure category: " + $Failure + '; got: ' + $captured)
      Assert-True ($captured -notmatch 'STAGE5_LOCAL_PREFLIGHT_PASS') 'Failed preflight emitted success'
    } else {
      Assert-True (-not $failed -and $captured -ceq 'STAGE5_LOCAL_PREFLIGHT_PASS') 'Preflight did not pass all gates'
    }
    Assert-True (@(Get-ChildItem -LiteralPath $temporaryRoot -Force).Count -eq 0) 'Preflight temporary archive leaked'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $stage 'output'))) 'Preflight created evidence output'
    Assert-True ((Test-Path -LiteralPath $identity) -and (Test-Path -LiteralPath ($identity + '.pub'))) 'Preflight removed identity'
    Assert-True ($global:stage5OfflineNetworkCalls -eq 0) 'Preflight attempted a network process'
  }
  try {
    foreach ($bom in @($false, $true)) {
      [IO.File]::WriteAllText($envPath, ($fixtureEnvironment -replace "`r?`n", "`r`n"), [Text.UTF8Encoding]::new($bom, $true))
      Invoke-Preflight
    }
    Invoke-Preflight @{MigrationReviewed=$true} 'MigrationReviewed'
    Invoke-Preflight @{AuthorizeLiveRun=$false} 'AuthorizeLiveRun'
    Invoke-Preflight @{SshUser='other'} 'requires SSH root'
    Invoke-Preflight @{VmHost='-unsafe'} 'Invalid SSH target'
    Invoke-Preflight @{RunId='../../unsafe'} 'canonical lowercase UUID'
    [IO.File]::WriteAllText($envPath, 'DB_PASSWORD=private-marker==', $utf8)
    Invoke-Preflight @{} 'Missing environment key'
    [IO.File]::WriteAllText($envPath, ($fixtureEnvironment -replace 'SPRING_FLYWAY_ENABLED=false', 'SPRING_FLYWAY_ENABLED=true'), $utf8)
    Invoke-Preflight @{} 'Flyway false'
    [IO.File]::WriteAllText($envPath, ($fixtureEnvironment -replace 'STAGE5_MAINTENANCE_APPROVED=true', 'STAGE5_MAINTENANCE_APPROVED=false'), $utf8)
    Invoke-Preflight @{} 'approved maintenance'
    [IO.File]::WriteAllText($envPath, $fixtureEnvironment, $utf8)
    [IO.File]::WriteAllText(($identity + '.pub'), 'private-marker', $utf8)
    Invoke-Preflight @{} 'dedicated temporary'
    [IO.File]::WriteAllText(($identity + '.pub'), 'ssh-ed25519 ZmFrZQ== volunteerflow-stage4d-11111111-1111-1111-1111-111111111111', $utf8)
    [IO.File]::WriteAllText($identity, 'private-marker', $utf8)
    Invoke-Preflight @{} 'private key format'
    [IO.File]::WriteAllText($identity, "-----BEGIN OPENSSH PRIVATE KEY-----`nZmFrZQ==`n-----END OPENSSH PRIVATE KEY-----`n", $utf8)
    & $git -C $repo branch -m wrong-branch
    Invoke-Preflight @{} 'Expected feat/stage-5'
    & $git -C $repo branch -m feat/stage-5
    [IO.File]::WriteAllText((Join-Path $repo 'untracked'), 'fixture', $utf8)
    Invoke-Preflight @{} 'clean committed'
    Remove-Item -LiteralPath (Join-Path $repo 'untracked')
    $archiveFailureSource = [regex]::Replace($offlineSource, '(?m)^& \$git [^\r\n]* archive [^\r\n]*', "throw 'Forced local archive failure'")
    Assert-True ($archiveFailureSource -cne $offlineSource) 'Archive failure double was not installed'
    [IO.File]::WriteAllText($fixtureRunner, $archiveFailureSource, $utf8)
    Commit-Fixture
    Invoke-Preflight @{} 'Forced local archive failure'
    [IO.File]::WriteAllText($fixtureRunner, $offlineSource, $utf8)
    Commit-Fixture
    # Exercise real normal-path JSON writing, with the network boundary blocked before process creation.
    $global:stage5OfflineNetworkCalls = 0
    $normalArguments = @{VmHost='vm.invalid';IdentityFile=$identity;ComposeEnvFile=$envPath;AuthorizeLiveRun=$true;
      RunId='22222222-2222-2222-2222-222222222222'}
    $normalOutput = (& $fixtureRunner @normalArguments 2>&1 | Out-String)
    Assert-True ($normalOutput -notmatch 'private-marker|abc=def|ZmFrZQ') 'Normal-path diagnostic disclosed secrets'
    Assert-True ($global:stage5OfflineNetworkCalls -eq 1) 'Normal-path network double did not fail before process creation'
    $statusPath = Join-Path $stage 'output/22222222-2222-2222-2222-222222222222/run-status.json'
    $statusBytes = [IO.File]::ReadAllBytes($statusPath)
    Assert-True ($statusBytes[0] -eq 123) 'JSON evidence must use UTF-8 without BOM on both PowerShell versions'
    $status = $utf8.GetString($statusBytes) | ConvertFrom-Json
    Assert-True ($status.remoteExit -eq 1 -and $status.keyRemovalStatus -eq 'not-attempted') 'Failed normal run must preserve failure and not remove key'
    Assert-True (@(Get-ChildItem -LiteralPath $temporaryRoot -Force).Count -eq 0) 'Normal failure leaked temporary archive'
    Remove-Item -LiteralPath (Join-Path $stage 'output') -Recurse -Force
    $global:stage5OfflineNetworkCalls = 0
    # Missing archive member fails AFTER archive creation; finally must still clean temp.
    Remove-Item -LiteralPath (Join-Path $stage 'acceptance.py')
    Commit-Fixture
    Invoke-Preflight @{} 'Committed driver hash failed'
  } finally {
    $env:TEMP = $oldTemp; $env:TMP = $oldTmp
  }
  & (Join-Path $PSScriptRoot 'test-native-tools.ps1') | Out-Null
  Assert-True ($global:stage5OfflineNetworkCalls -eq 0) 'Unexpected offline network activity'
} finally {
  $resolvedRoot = [IO.Path]::GetFullPath($root)
  if ([IO.Path]::GetFileName($resolvedRoot) -notmatch '^stage5-ps51-test-[a-f0-9]{32}$' -or
      [IO.Path]::GetDirectoryName($resolvedRoot) -ne [IO.Path]::GetTempPath().TrimEnd('\','/')) { throw 'Unsafe fixture cleanup target' }
  Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}
$versionLabel = if ($PowerShell7Check) { '7' } else { '5.1' }
Write-Output "PowerShell $versionLabel compatibility offline PASS ($assertions assertions)"
