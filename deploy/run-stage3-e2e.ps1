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

Import-Module (Join-Path $PSScriptRoot 'stage3-env.psm1') -Force

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$defaultComposeEnv = Join-Path $repositoryRoot 'deploy/.env'
$startLocation = (Get-Location).Path
$runId = [Guid]::NewGuid().ToString('D').ToLowerInvariant()
$primaryFailure = $null
$cleanupFailure = $null

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
$composeEnvironment = Read-Stage3EnvironmentFile -Path $resolvedComposeEnv

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

$additionalEnvironment = @{
  PLAYWRIGHT_BASE_URL = $normalizedBaseUrl
  S3_E2E_RUN_ID = $runId
  STAGE3_COMPOSE_ENV_FILE = $resolvedComposeEnv
}

try {
  Invoke-WithStage3Environment `
    -Environment $composeEnvironment `
    -AdditionalEnvironment $additionalEnvironment `
    -Operation {
      try {
        Invoke-Fixture 'seed'
        Invoke-InLocation $frontendRoot {
          & npx --no-install playwright test e2e/stage3.spec.ts --project=chromium
          if ($LASTEXITCODE -ne 0) {
            throw 'Stage 3 Playwright scenario failed'
          }
        }
      } catch {
        $script:primaryFailure = $_
      } finally {
        try {
          Invoke-Fixture 'cleanup'
        } catch {
          $script:cleanupFailure = $_
        }
      }
    }
} finally {
  Set-Location -LiteralPath $startLocation
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
