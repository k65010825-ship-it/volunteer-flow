[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$ApplicationLocalFile,

  [Parameter()]
  [string]$OutputFile = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function ConvertFrom-SimpleYamlScalar([string]$RawValue) {
  $value = $RawValue.Trim()
  if ($value.StartsWith("'") -and $value.EndsWith("'") -and $value.Length -ge 2) {
    return $value.Substring(1, $value.Length - 2).Replace("''", "'")
  }
  if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
    return ($value | ConvertFrom-Json)
  }
  return $value
}

function Read-SimpleYaml([string]$Path) {
  $values = @{}
  $stack = [Collections.Generic.List[object]]::new()
  $lineNumber = 0
  foreach ($rawLine in [IO.File]::ReadLines($Path, [Text.Encoding]::UTF8)) {
    $lineNumber++
    if ([string]::IsNullOrWhiteSpace($rawLine) -or $rawLine.TrimStart().StartsWith('#')) {
      continue
    }
    if ($rawLine.Contains("`t") -or $rawLine -notmatch '^(?<indent> *)(?<key>[^:#]+):(?<value>.*)$') {
      throw "Unsupported YAML structure at line $lineNumber"
    }
    $indent = $Matches.indent.Length
    $key = $Matches.key.Trim()
    $rawValue = $Matches.value
    while ($stack.Count -gt 0 -and [int]$stack[$stack.Count - 1].Indent -ge $indent) {
      $stack.RemoveAt($stack.Count - 1)
    }
    $pathParts = @($stack | ForEach-Object { [string]$_.Key }) + $key
    $propertyPath = $pathParts -join '.'
    if ([string]::IsNullOrWhiteSpace($rawValue)) {
      $stack.Add([pscustomobject]@{ Indent = $indent; Key = $key })
    } else {
      if ($values.ContainsKey($propertyPath)) {
        throw "Duplicate YAML property $propertyPath"
      }
      $values[$propertyPath] = ConvertFrom-SimpleYamlScalar $rawValue
    }
  }
  return $values
}

function Require-Value([hashtable]$Values, [string]$Name) {
  if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
    throw "application-local.yml is missing $Name"
  }
  return [string]$Values[$Name]
}

function Quote-DotEnv([string]$Value) {
  if ($Value -match "[`0`r`n]") {
    throw 'Environment values must be single-line text'
  }
  return "'" + $Value.Replace('\', '\\').Replace("'", "\'") + "'"
}

$resolvedSource = (Resolve-Path -LiteralPath $ApplicationLocalFile).Path
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
  $OutputFile = Join-Path $PSScriptRoot '.env'
}
$target = [IO.Path]::GetFullPath($OutputFile)
$parent = [IO.Path]::GetDirectoryName($target)
if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
  throw 'OutputFile parent directory must already exist'
}

$values = Read-SimpleYaml $resolvedSource
$jdbcUrl = Require-Value $values 'spring.datasource.url'
if ($jdbcUrl -notmatch '^jdbc:mysql://(?<host>[^:/?]+):(?<port>[0-9]{1,5})/(?<database>[^?]+)\?.+$') {
  throw 'spring.datasource.url must be a host:port MySQL JDBC URL'
}
$dbHost = $Matches.host
$dbPort = $Matches.port
$dbName = $Matches.database
$dbUsername = Require-Value $values 'spring.datasource.username'
$dbPassword = Require-Value $values 'spring.datasource.password'
$jwtSecret = Require-Value $values 'volunteerflow.auth.jwt.secret'
try {
  $jwtBytes = [Convert]::FromBase64String($jwtSecret)
} catch {
  throw 'volunteerflow.auth.jwt.secret must use standard Base64 encoding'
}
if ($jwtBytes.Length -lt 32) {
  throw 'volunteerflow.auth.jwt.secret must decode to at least 32 bytes'
}
$redisHost = Require-Value $values 'spring.data.redis.host'
$redisPort = Require-Value $values 'spring.data.redis.port'
$redisDatabase = Require-Value $values 'spring.data.redis.database'
$redisConnectTimeout = Require-Value $values 'spring.data.redis.connect-timeout'
$redisCommandTimeout = Require-Value $values 'spring.data.redis.timeout'

foreach ($portEntry in @(@('DB_PORT', $dbPort), @('REDIS_PORT', $redisPort))) {
  if ($portEntry[1] -notmatch '^[0-9]{1,5}$' -or [int]$portEntry[1] -lt 1 -or [int]$portEntry[1] -gt 65535) {
    throw "$($portEntry[0]) must be an integer from 1 to 65535"
  }
}

$lines = @(
  "DB_HOST=$(Quote-DotEnv $dbHost)",
  "DB_PORT=$(Quote-DotEnv $dbPort)",
  "DB_NAME=$(Quote-DotEnv $dbName)",
  "DB_USERNAME=$(Quote-DotEnv $dbUsername)",
  "DB_PASSWORD=$(Quote-DotEnv $dbPassword)",
  '',
  "JWT_SECRET=$(Quote-DotEnv $jwtSecret)",
  'SPRING_FLYWAY_ENABLED=false',
  '',
  "REDIS_HOST=$(Quote-DotEnv $redisHost)",
  "REDIS_PORT=$(Quote-DotEnv $redisPort)",
  "REDIS_PASSWORD=''",
  "REDIS_DATABASE=$(Quote-DotEnv $redisDatabase)",
  "REDIS_CONNECT_TIMEOUT=$(Quote-DotEnv $redisConnectTimeout)",
  "REDIS_COMMAND_TIMEOUT=$(Quote-DotEnv $redisCommandTimeout)",
  '',
  'DYNAMIC_CHECKIN_WINDOW=30s',
  'DYNAMIC_CHECKIN_TTL=45s',
  'DYNAMIC_CHECKIN_TOKEN_BYTES=24',
  '',
  'RATE_LIMIT_ENABLED=true',
  "RATE_LIMIT_KEY_SECRET='runner-replaces-this-value'",
  'RATE_LIMIT_LOGIN_IP_LIMIT=20',
  'RATE_LIMIT_LOGIN_IP_WINDOW=1m',
  'RATE_LIMIT_LOGIN_USERNAME_LIMIT=5',
  'RATE_LIMIT_LOGIN_USERNAME_WINDOW=5m',
  'RATE_LIMIT_REGISTRATION_LIMIT=3',
  'RATE_LIMIT_REGISTRATION_WINDOW=10s',
  'RATE_LIMIT_PROMOTION_LIMIT=5',
  'RATE_LIMIT_PROMOTION_WINDOW=1m',
  'RATE_LIMIT_STATIC_CHECKIN_LIMIT=5',
  'RATE_LIMIT_STATIC_CHECKIN_WINDOW=1m',
  'RATE_LIMIT_DYNAMIC_CHECKIN_LIMIT=10',
  'RATE_LIMIT_DYNAMIC_CHECKIN_WINDOW=1m',
  'RATE_LIMIT_LOCAL_LOGIN_MAX_ENTRIES=10000',
  'RATE_LIMIT_LOCAL_LOGIN_CLEANUP_INTERVAL=1m',
  '',
  "BOOTSTRAP_ADMIN_USERNAME=''",
  "BOOTSTRAP_ADMIN_PASSWORD=''",
  "BOOTSTRAP_ADMIN_REAL_NAME=''",
  "BOOTSTRAP_ADMIN_STUDENT_NUMBER=''",
  "BOOTSTRAP_ADMIN_CONTACT=''",
  '',
  'STAGE4D_APP_PORT=8088'
)

$temporary = [IO.Path]::Combine($parent, ([IO.Path]::GetFileName($target) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'))
try {
  [IO.File]::WriteAllLines($temporary, $lines, [Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temporary -Destination $target -Force
} finally {
  Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
}

Write-Output "Stage 4D environment file created: $target"
