Set-StrictMode -Version Latest

function Get-Stage3ManagedEnvironmentNames {
  return @(
    'DB_HOST',
    'DB_PORT',
    'DB_NAME',
    'DB_USERNAME',
    'DB_PASSWORD',
    'JWT_SECRET',
    'SPRING_FLYWAY_ENABLED',
    'BOOTSTRAP_ADMIN_USERNAME',
    'BOOTSTRAP_ADMIN_PASSWORD',
    'BOOTSTRAP_ADMIN_REAL_NAME',
    'BOOTSTRAP_ADMIN_STUDENT_NUMBER',
    'BOOTSTRAP_ADMIN_CONTACT',
    'APP_PORT'
  )
}

function Read-Stage3EnvironmentFile {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Path
  )

  $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
  $values = @{}
  $lineNumber = 0

  foreach ($rawLine in [IO.File]::ReadLines($resolvedPath, [Text.Encoding]::UTF8)) {
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
      if ($rawValue -notmatch "^'(?<value>(?:[^']|\\')*)'$" ) {
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

  $managedNames = Get-Stage3ManagedEnvironmentNames
  foreach ($name in $managedNames) {
    if (-not $values.ContainsKey($name)) {
      throw "Stage 3 environment is missing $name"
    }
  }

  foreach ($name in @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'APP_PORT')) {
    $value = [string]$values[$name]
    if ([string]::IsNullOrWhiteSpace($value) -or $value -match '(?i)replace-with|placeholder|change-me') {
      throw "Stage 3 environment has an unresolved required value for $name"
    }
  }

  foreach ($portName in @('DB_PORT', 'APP_PORT')) {
    $portValue = [string]$values[$portName]
    if ($portValue -notmatch '^\d{1,5}$' -or [int]$portValue -lt 1 -or [int]$portValue -gt 65535) {
      throw "$portName must be an integer from 1 to 65535"
    }
  }

  $jwtSecret = [string]$values['JWT_SECRET']
  if ($jwtSecret -match '\s') {
    throw 'JWT_SECRET must not contain whitespace'
  }
  try {
    $jwtBytes = [Convert]::FromBase64String($jwtSecret)
  } catch {
    throw 'JWT_SECRET must use standard Base64 encoding'
  }
  if ($jwtBytes.Length -lt 32) {
    throw 'JWT_SECRET must decode to at least 32 bytes'
  }

  $flywayEnabled = [string]$values['SPRING_FLYWAY_ENABLED']
  if ($flywayEnabled -cnotmatch '^(true|false)$') {
    throw 'SPRING_FLYWAY_ENABLED must be true or false'
  }

  $managedValues = @{}
  foreach ($name in $managedNames) {
    $managedValues[$name] = [string]$values[$name]
  }
  return $managedValues
}

function Invoke-WithStage3Environment {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [hashtable]$Environment,

    [Parameter()]
    [hashtable]$AdditionalEnvironment = @{},

    [Parameter(Mandatory = $true)]
    [scriptblock]$Operation
  )

  $effectiveEnvironment = @{}
  foreach ($entry in $Environment.GetEnumerator()) {
    $effectiveEnvironment[[string]$entry.Key] = [string]$entry.Value
  }
  foreach ($entry in $AdditionalEnvironment.GetEnumerator()) {
    $effectiveEnvironment[[string]$entry.Key] = [string]$entry.Value
  }

  $previousEnvironment = @{}
  foreach ($name in $effectiveEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  }

  try {
    foreach ($name in $effectiveEnvironment.Keys) {
      [Environment]::SetEnvironmentVariable($name, $effectiveEnvironment[$name], 'Process')
    }
    & $Operation
  } finally {
    foreach ($name in $effectiveEnvironment.Keys) {
      [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
  }
}

Export-ModuleMember -Function Get-Stage3ManagedEnvironmentNames, Read-Stage3EnvironmentFile, Invoke-WithStage3Environment
