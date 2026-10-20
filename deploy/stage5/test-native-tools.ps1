Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot '../lib/native-tools.psm1'
if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) { throw 'Native application resolver module is missing' }
$module = Import-Module $modulePath -Force -PassThru
$assertions = 0
function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
  $script:assertions++
}
function Expect-Rejection([scriptblock]$Action, [string]$MessagePattern) {
  $rejected = $false
  try { & $Action | Out-Null } catch {
    $rejected = $true
    Assert-True ($_.Exception.Message -match $MessagePattern) 'Expected a safe resolver rejection category'
    Assert-True ($_.Exception.Message -notmatch 'private-marker|DB_PASSWORD|JWT_SECRET') 'Resolver leaked a diagnostic secret'
  }
  Assert-True $rejected 'Unsafe/absent application was not rejected'
}
foreach ($toolName in @('git','python')) {
  $realCandidates = @(Get-Command $toolName -CommandType Application -All)
  # Retain real ApplicationInfo values; duplicate even on a single-install host.
  $multiple = @($realCandidates[0], $realCandidates[-1], $realCandidates[0])
  $legacy = [string]$multiple.Source
  Assert-True (-not (Test-Path -LiteralPath $legacy -PathType Leaf)) 'Legacy source projection must reproduce an invalid concatenated path'
  $selected = Select-ApprovedApplication -Name $toolName -Candidates $multiple -PreferredPaths @()
  Assert-True ($selected -is [string]) 'Resolver must return exactly one scalar string'
  Assert-True (@($selected).Count -eq 1) 'Resolver returned multiple paths'
  Assert-True (Test-Path -LiteralPath $selected -PathType Leaf) 'Selected application must exist'
  Assert-True ($selected -eq $realCandidates[0].Source) 'PATH fallback order changed'
  $again = Select-ApprovedApplication -Name $toolName -Candidates $multiple -PreferredPaths @()
  Assert-True ($again -eq $selected) 'Selection is not deterministic'
  $preferred = Select-ApprovedApplication -Name $toolName -Candidates $multiple -PreferredPaths @($realCandidates[-1].Source)
  Assert-True ($preferred -eq $realCandidates[-1].Source) 'Approved explicit preference must outrank PATH order'
}
Expect-Rejection { Select-ApprovedApplication -Name git -Candidates @() -PreferredPaths @() } 'No compatible application'
Expect-Rejection { Select-ApprovedApplication -Name git -Candidates @((Get-Alias cd)) -PreferredPaths @() } 'non-application'
$fake = [pscustomobject]@{Source='private-marker';CommandType='Application'}
Expect-Rejection { Select-ApprovedApplication -Name git -Candidates @($fake) -PreferredPaths @() } 'non-application'
Expect-Rejection { Resolve-ApprovedApplication -Name git -ExplicitPath (Join-Path $PSScriptRoot 'missing-private-marker.exe') } 'explicit application'
Expect-Rejection { Resolve-ApprovedApplication -Name python -ExplicitPath $PSCommandPath } 'explicit application'
$fixtureDirectory = Join-Path ([IO.Path]::GetTempPath()) ('stage5-native-tools-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureDirectory | Out-Null
$shim = Join-Path $fixtureDirectory 'git.cmd'
try {
  [IO.File]::WriteAllText($shim, '@echo off')
  $shimInfo = @(Get-Command -Name $shim -CommandType Application)
  Expect-Rejection { Select-ApprovedApplication -Name git -Candidates $shimInfo -PreferredPaths @() } 'No compatible application'
} finally {
  Remove-Item -LiteralPath $shim -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $fixtureDirectory -Force
}
$gitPath = @(Get-Command git -CommandType Application -All)[0].Source
$pythonProbe = & $module { param($Path) Test-ApplicationVersion -Name python -Path $Path } $gitPath
Assert-True (-not $pythonProbe) 'A non-Python executable must not satisfy Python version validation'
foreach ($toolName in @('git','python','ssh','scp','ssh-keygen')) {
  $selected = Resolve-ApprovedApplication -Name $toolName
  Assert-True ($selected -is [string] -and (Test-Path -LiteralPath $selected -PathType Leaf)) 'Live-path resolver did not return a single existing application'
}
Write-Output "Native resolver offline PASS ($assertions assertions)"
