[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('Config', 'Up', 'Ps', 'Logs', 'Down')]
  [string]$Action,

  [Parameter()]
  [string]$ComposeEnvFile = '',

  [Parameter()]
  [ValidateSet('backend', 'frontend-nginx')]
  [string]$Service = 'backend',

  [Parameter()]
  [ValidateRange(1, 10000)]
  [int]$Tail = 200
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'stage3-env.psm1') -Force

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'deploy/docker-compose.yml'
if ([string]::IsNullOrWhiteSpace($ComposeEnvFile)) {
  $ComposeEnvFile = Join-Path $repositoryRoot 'deploy/.env'
}
$resolvedComposeEnv = (Resolve-Path -LiteralPath $ComposeEnvFile).Path
$composeEnvironment = Read-Stage3EnvironmentFile -Path $resolvedComposeEnv

switch ($Action) {
  'Config' { $composeArguments = @('compose', '--file', $composeFile, '--env-file', $resolvedComposeEnv, 'config', '--quiet') }
  'Up' { $composeArguments = @('compose', '--file', $composeFile, '--env-file', $resolvedComposeEnv, 'up', '--build', '--detach') }
  'Ps' { $composeArguments = @('compose', '--file', $composeFile, '--env-file', $resolvedComposeEnv, 'ps') }
  'Logs' { $composeArguments = @('compose', '--file', $composeFile, '--env-file', $resolvedComposeEnv, 'logs', '--tail', [string]$Tail, $Service) }
  'Down' { $composeArguments = @('compose', '--file', $composeFile, '--env-file', $resolvedComposeEnv, 'down') }
}

$composeExitCode = 0
Invoke-WithStage3Environment -Environment $composeEnvironment -Operation {
  & docker @composeArguments
  $script:composeExitCode = $LASTEXITCODE
}

exit $composeExitCode
