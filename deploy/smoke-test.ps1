[CmdletBinding()]
param(
  [Parameter()]
  [ValidateNotNullOrEmpty()]
  [string]$BaseUrl = 'http://localhost:8088'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$normalizedBaseUrl = $BaseUrl.Trim().TrimEnd('/')
$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($normalizedBaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) -or
    $parsedBaseUrl.Scheme -notin @('http', 'https')) {
  throw 'BaseUrl must be an absolute HTTP or HTTPS URL'
}

try {
  $health = Invoke-RestMethod `
    -Method Get `
    -Uri "$normalizedBaseUrl/actuator/health/readiness" `
    -TimeoutSec 15 `
    -ErrorAction Stop
} catch {
  throw 'backend readiness request failed'
}

if ($health.status -ne 'UP') {
  throw 'backend readiness is not UP'
}

try {
  $root = Invoke-WebRequest `
    -Method Get `
    -Uri "$normalizedBaseUrl/" `
    -UseBasicParsing `
    -TimeoutSec 15 `
    -ErrorAction Stop
} catch {
  throw 'frontend root request failed'
}

if ($root.StatusCode -ne 200) {
  throw 'frontend root failed'
}

try {
  $deep = Invoke-WebRequest `
    -Method Get `
    -Uri "$normalizedBaseUrl/notifications" `
    -UseBasicParsing `
    -TimeoutSec 15 `
    -ErrorAction Stop
} catch {
  throw 'SPA fallback request failed'
}

if ($deep.StatusCode -ne 200 -or $deep.Content -notmatch '<div\s+id=["'']app["'']') {
  throw 'SPA fallback failed'
}

Write-Output 'Stage 3 public-entry smoke checks passed.'
