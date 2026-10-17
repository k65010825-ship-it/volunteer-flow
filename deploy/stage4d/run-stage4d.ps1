[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$VmHost,

  [Parameter()]
  [ValidateRange(1, 65535)]
  [int]$SshPort = 22,

  [Parameter()]
  [ValidateNotNullOrEmpty()]
  [string]$SshUser = 'root',

  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$IdentityFile,

  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$ComposeEnvFile,

  [Parameter()]
  [string]$RunId = '',

  [Parameter()]
  [string]$RepositoryRoot = '',

  [Parameter()]
  [string]$SshCommand = '',

  [Parameter()]
  [string]$ScpCommand = '',

  [Parameter()]
  [string]$GitCommand = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-NativeCommand([string]$Candidate, [string]$DefaultName) {
  if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
    return (Resolve-Path -LiteralPath $Candidate).Path
  }
  return (Get-Command $DefaultName -CommandType Application -ErrorAction Stop).Source
}

function Invoke-Native([string]$Command, [string[]]$Arguments, [string]$FailureMessage) {
  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw $FailureMessage
  }
}

if ($VmHost -notmatch '^[A-Za-z0-9.-]+$' -or $SshUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'VmHost or SshUser contains unsupported characters'
}
$parsedRunId = [Guid]::Empty
if ([string]::IsNullOrWhiteSpace($RunId)) {
  $parsedRunId = [Guid]::NewGuid()
} elseif (-not [Guid]::TryParseExact($RunId, 'D', [ref]$parsedRunId)) {
  throw 'RunId must be a canonical UUID'
}
$RunId = $parsedRunId.ToString('D').ToLowerInvariant()

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
  $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
} else {
  $RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
}
$resolvedIdentity = (Resolve-Path -LiteralPath $IdentityFile).Path
$resolvedComposeEnv = (Resolve-Path -LiteralPath $ComposeEnvFile).Path
if (-not (Test-Path -LiteralPath ($resolvedIdentity + '.pub') -PathType Leaf)) {
  throw 'IdentityFile must have a matching .pub file'
}

$environmentLines = [IO.File]::ReadAllLines($resolvedComposeEnv, [Text.Encoding]::UTF8)
foreach ($requiredName in @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'SPRING_FLYWAY_ENABLED', 'REDIS_HOST', 'REDIS_PORT')) {
  $matches = @($environmentLines | Where-Object { $_ -match ('^' + [Regex]::Escape($requiredName) + '=') })
  if ($matches.Count -ne 1 -or $matches[0] -match '(?i)replace-with|placeholder|change-me') {
    throw "Compose environment has an unresolved or duplicate $requiredName entry"
  }
}
if (-not ($environmentLines -contains 'SPRING_FLYWAY_ENABLED=false')) {
  throw 'The restored Stage 4D database requires SPRING_FLYWAY_ENABLED=false'
}

$ssh = Resolve-NativeCommand $SshCommand 'ssh'
$scp = Resolve-NativeCommand $ScpCommand 'scp'
$git = Resolve-NativeCommand $GitCommand 'git'

$branch = (& $git -C $RepositoryRoot rev-parse --abbrev-ref HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -ne 'feat/stage-4d') {
  throw 'Stage 4D acceptance must run from the feat/stage-4d branch'
}
$treeStatus = (& $git -C $RepositoryRoot status --porcelain --untracked-files=all | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrEmpty($treeStatus)) {
  throw 'Stage 4D acceptance requires a clean committed worktree'
}
$commit = (& $git -C $RepositoryRoot rev-parse HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
  throw 'Unable to resolve the committed Stage 4D revision'
}

$outputDirectory = Join-Path $RepositoryRoot "output/stage4d/$RunId"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ('volunteerflow-stage4d-run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
$archive = Join-Path $temporaryDirectory 'source.tar'
$protectedEnvironment = Join-Path $temporaryDirectory 'stage4d.env'

$randomBytes = New-Object byte[] 32
$randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
  $randomGenerator.GetBytes($randomBytes)
} finally {
  $randomGenerator.Dispose()
}
$rateLimitSecret = [Convert]::ToBase64String($randomBytes)
$protectedLines = @($environmentLines | Where-Object { $_ -notmatch '^RATE_LIMIT_KEY_SECRET=' })
$protectedLines += "RATE_LIMIT_KEY_SECRET=$rateLimitSecret"
[IO.File]::WriteAllLines($protectedEnvironment, $protectedLines, [Text.UTF8Encoding]::new($false))

Invoke-Native $git @('-C', $RepositoryRoot, 'archive', '--format=tar', '-o', $archive, 'HEAD') 'Git archive creation failed'
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
  throw 'Git archive was not created'
}

$remoteRoot = "/opt/volunteerflow-stage4d/$RunId"
$composeProject = "vf4d$($RunId.Replace('-', ''))"
$target = "$SshUser@$VmHost"
$sshArguments = @('-p', [string]$SshPort, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-i', $resolvedIdentity)
$scpArguments = @('-P', [string]$SshPort, '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-i', $resolvedIdentity)
$remoteMutationStarted = $false
$primaryStatus = 'not-started'
$recoveryStatus = 'not-reported'
$cleanupStatus = 'not-reported'
$evidenceDownloadStatus = 'not-attempted'
$keyRemovalStatus = 'not-attempted'
$keyRemovalErrorType = $null

try {
  Invoke-Native $ssh ($sshArguments + @($target, "install -d -m 700 '$remoteRoot'")) 'Remote run directory creation failed'
  $remoteMutationStarted = $true
  Invoke-Native $scp ($scpArguments + @($archive, $protectedEnvironment, "${target}:$remoteRoot/")) 'Stage 4D upload failed'
  $remoteCommand = "mkdir -p '$remoteRoot/repo' && tar -xf '$remoteRoot/source.tar' -C '$remoteRoot/repo' && chmod +x '$remoteRoot/repo/deploy/stage4d/remote-stage4d.sh' && '$remoteRoot/repo/deploy/stage4d/remote-stage4d.sh' --run-id '$RunId' --remote-root '$remoteRoot' --compose-project '$composeProject' --compose-env '$remoteRoot/stage4d.env' --commit '$commit'"
  & $ssh @sshArguments $target $remoteCommand
  if ($LASTEXITCODE -eq 0) {
    $primaryStatus = 'success'
  } else {
    $primaryStatus = 'failed'
  }
} catch {
  $primaryStatus = 'failed'
} finally {
  if ($remoteMutationStarted) {
    try {
      Invoke-Native $scp ($scpArguments + @('-r', "${target}:$remoteRoot/evidence/.", $outputDirectory)) 'Evidence download failed'
      $evidenceDownloadStatus = 'success'
    } catch {
      $evidenceDownloadStatus = 'failed'
    }

    $remoteStatusPath = Join-Path $outputDirectory 'run-status.json'
    if (Test-Path -LiteralPath $remoteStatusPath -PathType Leaf) {
      try {
        $remoteStatus = Get-Content -LiteralPath $remoteStatusPath -Raw | ConvertFrom-Json
        $recoveryStatus = [string]$remoteStatus.recoveryStatus
        $cleanupStatus = [string]$remoteStatus.cleanupStatus
        Move-Item -LiteralPath $remoteStatusPath -Destination (Join-Path $outputDirectory 'remote-run-status.json') -Force
      } catch {
        $recoveryStatus = 'invalid-remote-status'
        $cleanupStatus = 'invalid-remote-status'
      }
    }

    try {
      & (Join-Path $PSScriptRoot 'remove-temporary-ssh.ps1') `
        -VmHost $VmHost `
        -SshPort $SshPort `
        -SshUser $SshUser `
        -IdentityFile $resolvedIdentity `
        -SshCommand $ssh | Out-Null
      $keyRemovalStatus = 'success'
    } catch {
      $keyRemovalStatus = 'failed'
      $keyRemovalErrorType = $_.Exception.GetType().FullName
    }
  }

  $status = [ordered]@{
    runId = $RunId
    commit = $commit
    primaryStatus = $primaryStatus
    recoveryStatus = $recoveryStatus
    cleanupStatus = $cleanupStatus
    evidenceDownloadStatus = $evidenceDownloadStatus
    keyRemovalStatus = $keyRemovalStatus
    keyRemovalErrorType = $keyRemovalErrorType
  }
  $status | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory 'run-status.json') -Encoding utf8
  Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
  $rateLimitSecret = $null
  [Array]::Clear($randomBytes, 0, $randomBytes.Length)
}

if ($primaryStatus -ne 'success' -or $recoveryStatus -ne 'success' -or
    $cleanupStatus -ne 'success' -or $evidenceDownloadStatus -ne 'success' -or
    $keyRemovalStatus -ne 'success') {
  throw "Stage 4D acceptance failed; inspect $outputDirectory"
}
Write-Output "Stage 4D evidence: $outputDirectory"
