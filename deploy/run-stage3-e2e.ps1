[CmdletBinding()]
param(
  [Parameter()]
  [ValidateNotNullOrEmpty()]
  [string]$BaseUrl = 'http://localhost:8088',

  [Parameter()]
  [string]$BackendConfigPath = '',

  [Parameter()]
  [string]$ComposeEnvFile = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$defaultBackendConfig = Join-Path $backendRoot 'config/application-local.yml'
$defaultComposeEnv = Join-Path $repositoryRoot 'deploy/.env'
$startLocation = (Get-Location).Path
$runId = [Guid]::NewGuid().ToString('D').ToLowerInvariant()
$primaryFailure = $null
$cleanupFailure = $null
$resolvedBackendConfig = $null

$normalizedBaseUrl = $BaseUrl.Trim().TrimEnd('/')
$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($normalizedBaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) -or
    $parsedBaseUrl.Scheme -notin @('http', 'https')) {
  throw 'BaseUrl must be an absolute HTTP or HTTPS URL'
}

if ([string]::IsNullOrWhiteSpace($BackendConfigPath)) {
  if (-not [string]::IsNullOrWhiteSpace($env:STAGE3_E2E_BACKEND_CONFIG)) {
    $BackendConfigPath = $env:STAGE3_E2E_BACKEND_CONFIG
  } elseif (Test-Path -LiteralPath $defaultBackendConfig -PathType Leaf) {
    $BackendConfigPath = $defaultBackendConfig
  }
}

if (-not [string]::IsNullOrWhiteSpace($BackendConfigPath)) {
  $resolvedBackendConfig = (Resolve-Path -LiteralPath $BackendConfigPath).Path
} elseif (@('DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET') | Where-Object {
    [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
  }) {
  throw 'Provide BackendConfigPath/STAGE3_E2E_BACKEND_CONFIG or existing DB and JWT environment variables'
}

if ([string]::IsNullOrWhiteSpace($ComposeEnvFile)) {
  $ComposeEnvFile = $defaultComposeEnv
}
$resolvedComposeEnv = (Resolve-Path -LiteralPath $ComposeEnvFile).Path

$managedEnvironment = @(
  'PLAYWRIGHT_BASE_URL',
  'S3_E2E_RUN_ID',
  'STAGE3_COMPOSE_ENV_FILE',
  'SPRING_CONFIG_ADDITIONAL_LOCATION'
)
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
  if ($resolvedBackendConfig) {
    $configUri = ([Uri]$resolvedBackendConfig).AbsoluteUri
    $env:SPRING_CONFIG_ADDITIONAL_LOCATION = $configUri
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
