[CmdletBinding()]
param(
  [Parameter()]
  [ValidateNotNullOrEmpty()]
  [string]$BaseUrl = 'http://localhost:8088',

  [Parameter()]
  [string]$ComposeEnvFile = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$defaultComposeEnv = Join-Path $repositoryRoot 'deploy/.env'
$startLocation = (Get-Location).Path
$runId = [Guid]::NewGuid().ToString('D').ToLowerInvariant()
$primaryFailure = $null
$cleanupFailure = $null
$fixtureEnvironmentNames = @(
  'DB_HOST',
  'DB_PORT',
  'DB_NAME',
  'DB_USERNAME',
  'DB_PASSWORD',
  'JWT_SECRET',
  'BOOTSTRAP_ADMIN_USERNAME',
  'BOOTSTRAP_ADMIN_PASSWORD',
  'BOOTSTRAP_ADMIN_REAL_NAME',
  'BOOTSTRAP_ADMIN_STUDENT_NUMBER',
  'BOOTSTRAP_ADMIN_CONTACT'
)
$requiredFixtureEnvironmentNames = @(
  'DB_HOST',
  'DB_PORT',
  'DB_NAME',
  'DB_USERNAME',
  'DB_PASSWORD',
  'JWT_SECRET'
)

function Read-ComposeEnvironmentFile([string]$Path) {
  $values = @{}
  $lineNumber = 0

  foreach ($rawLine in [IO.File]::ReadLines($Path, [Text.Encoding]::UTF8)) {
    $lineNumber++
    $line = $rawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
      continue
    }
    if ($line -notmatch '^(?<name>[A-Za-z_][A-Za-z0-9_]*)=(?<raw>.*)$') {
      throw "Invalid environment entry at line $lineNumber"
    }

    $name = $Matches['name']
    if ($values.ContainsKey($name)) {
      throw "Duplicate environment key $name"
    }
    $rawValue = $Matches['raw'].Trim()
    if ($rawValue.StartsWith("'")) {
      if ($rawValue -notmatch "^'(?<value>(?:[^']|\\')*)'$") {
        throw "Invalid single-quoted value for $name"
      }
      $value = [Regex]::Replace($Matches['value'], "\\'", "'")
    } else {
      if ($rawValue -notmatch '^[A-Za-z0-9._:/-]*$') {
        throw "Unquoted environment value for $name contains unsupported characters"
      }
      $value = $rawValue
    }
    $values[$name] = $value
  }

  return $values
}

$normalizedBaseUrl = $BaseUrl.Trim().TrimEnd('/')
$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($normalizedBaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) -or
    $parsedBaseUrl.Scheme -notin @('http', 'https')) {
  throw 'BaseUrl must be an absolute HTTP or HTTPS URL'
}

if ([string]::IsNullOrWhiteSpace($ComposeEnvFile)) {
  $ComposeEnvFile = $defaultComposeEnv
}
$resolvedComposeEnv = (Resolve-Path -LiteralPath $ComposeEnvFile).Path
$composeEnvironment = Read-ComposeEnvironmentFile $resolvedComposeEnv

foreach ($name in $fixtureEnvironmentNames) {
  if (-not $composeEnvironment.ContainsKey($name)) {
    throw "Compose environment is missing $name"
  }
}
foreach ($name in $requiredFixtureEnvironmentNames) {
  $value = [string]$composeEnvironment[$name]
  if ([string]::IsNullOrWhiteSpace($value) -or $value -match '(?i)replace-with|placeholder|change-me') {
    throw "Compose environment has an unresolved required value for $name"
  }
}
if ([string]$composeEnvironment['DB_PORT'] -notmatch '^\d{1,5}$' -or
    [int]$composeEnvironment['DB_PORT'] -lt 1 -or
    [int]$composeEnvironment['DB_PORT'] -gt 65535) {
  throw 'Compose environment DB_PORT must be an integer from 1 to 65535'
}
try {
  $jwtBytes = [Convert]::FromBase64String([string]$composeEnvironment['JWT_SECRET'])
} catch {
  throw 'Compose environment JWT_SECRET must use standard Base64 encoding'
}
if ($jwtBytes.Length -lt 32) {
  throw 'Compose environment JWT_SECRET must decode to at least 32 bytes'
}

$managedEnvironment = @(
  'PLAYWRIGHT_BASE_URL',
  'S3_E2E_RUN_ID',
  'STAGE3_COMPOSE_ENV_FILE'
) + $fixtureEnvironmentNames
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) {
  $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Invoke-InLocation([string]$Path, [scriptblock]$Operation) {
  Push-Location -LiteralPath $Path
  try {
    & $Operation
  } finally {
    Pop-Location
  }
}

function Invoke-Fixture([string]$FixtureAction) {
  $fixtureActionValue = $FixtureAction
  Invoke-InLocation $backendRoot {
    & .\mvnw.cmd `
      '-Dtest=Stage3E2eFixtureIT' `
      "-Dstage3.e2e.action=$fixtureActionValue" `
      "-Dstage3.e2e.run-id=$runId" `
      test
    if ($LASTEXITCODE -ne 0) {
      throw "Stage 3 E2E fixture $fixtureActionValue failed"
    }
  }
}

try {
  $env:PLAYWRIGHT_BASE_URL = $normalizedBaseUrl
  $env:S3_E2E_RUN_ID = $runId
  $env:STAGE3_COMPOSE_ENV_FILE = $resolvedComposeEnv
  foreach ($name in $fixtureEnvironmentNames) {
    [Environment]::SetEnvironmentVariable($name, [string]$composeEnvironment[$name])
  }

  Invoke-Fixture 'seed'
  Invoke-InLocation $frontendRoot {
    & npx --no-install playwright test e2e/stage3.spec.ts --project=chromium
    if ($LASTEXITCODE -ne 0) {
      throw 'Stage 3 Playwright scenario failed'
    }
  }
} catch {
  $primaryFailure = $_
} finally {
  try {
    Invoke-Fixture 'cleanup'
  } catch {
    $cleanupFailure = $_
  } finally {
    foreach ($name in $managedEnvironment) {
      [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name])
    }
    Set-Location -LiteralPath $startLocation
  }
}

if ($primaryFailure) {
  if ($cleanupFailure) {
    throw 'Stage 3 E2E failed and exact fixture cleanup also failed; inspect both command outputs'
  }
  throw $primaryFailure
}
if ($cleanupFailure) {
  throw $cleanupFailure
}

Write-Output 'Stage 3 public Nginx browser flow passed and exact fixture cleanup reached zero rows.'
