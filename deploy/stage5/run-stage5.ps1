[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$VmHost,
  [Parameter(Mandatory = $true)][string]$IdentityFile,
  [Parameter(Mandatory = $true)][string]$ComposeEnvFile,
  [ValidateRange(1, 65535)][int]$SshPort = 22,
  [string]$SshUser = 'root',
  [string]$RunId = '',
  [switch]$AuthorizeLiveRun,
  [switch]$LocalPreflightOnly,
  [switch]$MigrationReviewed
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $AuthorizeLiveRun) { throw 'AuthorizeLiveRun is required; static checks do not authorize a live run.' }
if ($LocalPreflightOnly -and $MigrationReviewed) { throw 'LocalPreflightOnly refuses MigrationReviewed; migration approval is a separate live gate.' }
if ($SshUser -cne 'root') { throw 'This approved Stage5 deployment requires SSH root; other operators need separate review.' }
if ($VmHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$' -or $SshUser -notmatch '^[A-Za-z0-9_][A-Za-z0-9._-]*$') {
  throw 'Invalid SSH target'
}
if (-not $RunId) { $RunId = [Guid]::NewGuid().ToString('D') }
if ($RunId -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') { throw 'RunId must be a canonical lowercase UUID' }
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$identity = (Resolve-Path -LiteralPath $IdentityFile).Path
$envFile = (Resolve-Path -LiteralPath $ComposeEnvFile).Path
if ([IO.File]::ReadAllText($identity) -cnotmatch '\A-----BEGIN OPENSSH PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]+\r?\n-----END OPENSSH PRIVATE KEY-----(\r?\n)?\z') {
  throw 'A dedicated temporary OpenSSH private key format is required'
}
$publicKey = [IO.File]::ReadAllText($identity + '.pub').Trim()
# Reuse the audited Stage4D bootstrap/removal lifecycle; never accept a personal key.
if ($publicKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+ volunteerflow-stage4d-[0-9a-f-]+$') { throw 'A dedicated temporary Stage4D-format Ed25519 key is required' }
function Read-Stage5Environment([string]$Path) {
  $result = @{}
  try {
    # Read bytes explicitly: accept only UTF-8, with optional UTF-8 BOM (not UTF-16).
    $bytes = [IO.File]::ReadAllBytes($Path)
    $offset = 0
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) { $offset = 3 }
    $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes, $offset, $bytes.Length - $offset)
    $allowed = @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','JWT_SECRET','REDIS_HOST','REDIS_PORT',
      'RATE_LIMIT_KEY_SECRET','SPRING_FLYWAY_ENABLED','STAGE5_MYSQL_CONTAINER','STAGE5_REDIS_CONTAINER',
      'STAGE5_MAINTENANCE_APPROVED','REDIS_PASSWORD','REDIS_DATABASE','OUTBOX_POLL_INTERVAL','OUTBOX_BATCH_SIZE',
      'OUTBOX_LEASE','OUTBOX_SEND_TIMEOUT','OUTBOX_RETRY_INITIAL','OUTBOX_RETRY_MAX','OUTBOX_RETENTION',
      'OUTBOX_RETENTION_INTERVAL','OUTBOX_RETENTION_BATCH_SIZE','PROMOTION_RECONCILIATION_SCAN_DELAY',
      'PROMOTION_RECONCILIATION_BATCH_SIZE','PROMOTION_RECONCILIATION_LEASE',
      'PROMOTION_RECONCILIATION_RETRY_INITIAL','PROMOTION_RECONCILIATION_RETRY_MAX')
    foreach ($line in [regex]::Split($text, '\r?\n')) {
      if (-not $line -or $line.StartsWith('#')) { continue }
      # IndexOf(char)/Substring have the same overload behavior in .NET Framework and .NET.
      $separator = $line.IndexOf([char]'=')
      if ($separator -lt 1) { throw 'Invalid entry' }
      $key = $line.Substring(0, $separator)
      $value = $line.Substring($separator + 1)
      if ($key -cnotmatch '^[A-Z][A-Z0-9_]*$' -or $allowed -cnotcontains $key -or $result.ContainsKey($key) -or
          $value -match '[\s\x00-\x1f$''"\\#]' -or $value -match '(?i)replace-with|placeholder|change-me') { throw 'Invalid entry' }
      $result[$key] = $value
    }
    return $result
  } catch { $result.Clear(); throw 'Invalid or unresolved environment; values suppressed' }
}
$names = @{}
$temporaryDirectory = $null
$archive = $null
$localPreflightPassed = $false
try {
$names = Read-Stage5Environment $envFile
foreach ($required in @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','JWT_SECRET','REDIS_HOST','REDIS_PORT','RATE_LIMIT_KEY_SECRET','STAGE5_MYSQL_CONTAINER','STAGE5_REDIS_CONTAINER')) {
  if (-not $names.ContainsKey($required) -or -not $names[$required]) { throw "Missing environment key: $required" }
}
if ($names['SPRING_FLYWAY_ENABLED'] -ne 'false' -or $names['STAGE5_MAINTENANCE_APPROVED'] -ne 'true') { throw 'Flyway false and explicitly approved maintenance window required' }
Import-Module (Join-Path $PSScriptRoot '../lib/native-tools.psm1') -Force
$ssh = Resolve-ApprovedApplication -Name ssh
$scp = Resolve-ApprovedApplication -Name scp
$git = Resolve-ApprovedApplication -Name git
$python = Resolve-ApprovedApplication -Name python
$branch = (& $git -C $repositoryRoot branch --show-current | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -ne 'feat/stage-5') { throw 'Expected feat/stage-5 branch' }
$dirty = (& $git -C $repositoryRoot status --porcelain --untracked-files=all | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'A clean committed worktree is required' }
$commit = (& $git -C $repositoryRoot rev-parse HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid revision' }
$output = Join-Path $PSScriptRoot "output/$RunId"
if (Test-Path -LiteralPath $output) { throw 'Run ID already used; preserve prior evidence' }
# Both paths use the same local gates, but never stage an archive inside evidence.
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$temporaryCandidate = [IO.Path]::GetFullPath((Join-Path $temporaryRoot ('volunteerflow-stage5-preflight-' + [Guid]::NewGuid().ToString('N'))))
if ([IO.Path]::GetDirectoryName($temporaryCandidate) -ne $temporaryRoot -or
    [IO.Path]::GetFileName($temporaryCandidate) -notmatch '^volunteerflow-stage5-preflight-[a-f0-9]{32}$') { throw 'Invalid local temporary directory' }
New-Item -ItemType Directory -Path $temporaryCandidate -ErrorAction Stop | Out-Null
$temporaryDirectory = $temporaryCandidate
if (((Get-Item -LiteralPath $temporaryDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Invalid local temporary directory' }
$archive = Join-Path $temporaryDirectory 'source.tar'
& $git -C $repositoryRoot archive --format=tar -o $archive HEAD
if ($LASTEXITCODE -ne 0) { throw 'Git archive failed' }
# Hash exact archive LF bytes, never checkout CRLF or decoded PowerShell stdout.
try { $driverSha256 = (& $python -B (Join-Path $PSScriptRoot 'archive-driver-hash.py') $archive | Out-String).Trim() }
catch { throw 'Committed driver hash failed' }
if ($LASTEXITCODE -ne 0 -or $driverSha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Committed driver hash failed' }
if ($LocalPreflightOnly) { $localPreflightPassed = $true; return }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$remoteRoot = "/opt/volunteerflow-stage5/$RunId"
$project = 'vf5' + $RunId.Replace('-', '')
$target = "$SshUser@$VmHost"
$options = @('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3','-i',$identity)
$sshArgs = @('-p', [string]$SshPort) + $options
$scpArgs = @('-P', [string]$SshPort) + $options
$created = $false
$remoteExit = 1
$evidenceDownloadStatus = 'not-attempted'
$keyRemovalStatus = 'not-attempted'
$keyRemovalExitCode = $null
$secretRemovalStatus = 'not-attempted'
$remoteStatus = $null
try {
  # mkdir (not -p) refuses reuse. All interpolated values have strict allowlists.
  & $ssh @sshArgs $target "umask 077; test ! -e '$remoteRoot' && mkdir -p '/opt/volunteerflow-stage5' && mkdir '$remoteRoot'"
  if ($LASTEXITCODE -ne 0) { throw 'Remote root creation refused' }
  $created = $true
  & $scp @scpArgs $archive "${target}:$remoteRoot/source.tar"
  if ($LASTEXITCODE -ne 0) { throw 'Source upload failed' }
  & $scp @scpArgs $envFile "${target}:$remoteRoot/stage5.env"
  if ($LASTEXITCODE -ne 0) { throw 'Protected environment upload failed' }
  $migrationFlag = ''
  if ($MigrationReviewed) { $migrationFlag = '--migration-reviewed' }
  $command = "umask 077; mkdir '$remoteRoot/repo' && tar -xf '$remoteRoot/source.tar' -C '$remoteRoot/repo' && bash '$remoteRoot/repo/deploy/stage5/remote-stage5.sh' --run-id '$RunId' --remote-root '$remoteRoot' --compose-project '$project' --compose-env '$remoteRoot/stage5.env' --commit '$commit' --driver-sha256 '$driverSha256' --authorize-live-run $migrationFlag"
  & $ssh @sshArgs $target $command
  $remoteExit = $LASTEXITCODE
} catch {
  $remoteExit = 1
} finally {
  if ($created) {
    try {
      & $scp @scpArgs '-r' "${target}:$remoteRoot/evidence/." $output
      if ($LASTEXITCODE -ne 0) { throw 'Evidence download failed' }
      $evidenceDownloadStatus = 'success'
      $remoteStatusPath = Join-Path $output 'run-status.json'
      $remoteStatus = [IO.File]::ReadAllText($remoteStatusPath, [Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json
      Move-Item -LiteralPath $remoteStatusPath -Destination (Join-Path $output 'remote-run-status.json')
    } catch { $evidenceDownloadStatus = 'failed' }
    # Keep protected backup and failed-run files for recovery; remove only exact credential paths.
    try {
      if ($null -ne $remoteStatus -and $remoteStatus.cleanupStatus -eq 'success') {
        & $ssh @sshArgs $target "test '$remoteRoot' = '/opt/volunteerflow-stage5/$RunId' && rm -f -- '$remoteRoot/stage5.env' '$remoteRoot/container.env' '$remoteRoot/host-private/mysql.cnf' '$remoteRoot/private/manifest-private.json'"
        if ($LASTEXITCODE -ne 0) { throw 'Exact credential cleanup failed' }
        $secretRemovalStatus = 'success'
      } else { $secretRemovalStatus = 'retained-protected-for-recovery' }
    } catch { $secretRemovalStatus = 'failed' }
    try {
      & $python -B (Join-Path $PSScriptRoot 'remove-temporary-key.py') --host $VmHost --user $SshUser --port $SshPort --identity $identity --status-file (Join-Path $output 'key-removal-status.json') --ssh $ssh
      $keyRemovalExitCode = $LASTEXITCODE
      if ($keyRemovalExitCode -ne 0) { throw 'Bounded temporary-key removal failed' }
      $keyRemovalStatus = 'success'
    } catch { $keyRemovalStatus = 'failed' }
  }
  $statusJson = [ordered]@{runId=$RunId;commit=$commit;remoteExit=$remoteExit;evidenceDownloadStatus=$evidenceDownloadStatus;
    keyRemovalStatus=$keyRemovalStatus;keyRemovalExitCode=$keyRemovalExitCode;secretRemovalStatus=$secretRemovalStatus;remote=$remoteStatus} |
    ConvertTo-Json -Depth 8
  [IO.File]::WriteAllText((Join-Path $output 'run-status.json'), $statusJson, [Text.UTF8Encoding]::new($false, $true))
}
if ($remoteExit -ne 0) { Write-Output "Stage5 failed; protected run evidence: $output"; exit $remoteExit }
if ($evidenceDownloadStatus -ne 'success' -or $keyRemovalStatus -ne 'success' -or $secretRemovalStatus -ne 'success' -or
    $null -eq $remoteStatus -or $remoteStatus.primaryStatus -ne 'success' -or $remoteStatus.recoveryStatus -ne 'success' -or
    $remoteStatus.cleanupStatus -ne 'success' -or $remoteStatus.secretScanStatus -ne 'success') { throw 'Stage5 final gates failed' }
Write-Output "Stage5 evidence downloaded: $output"
} finally {
  $names.Clear()
  if ($archive -and (Test-Path -LiteralPath $archive)) { Remove-Item -LiteralPath $archive -Force }
  # No recursion: only this invocation's exact archive and now-empty directory are removed.
  if ($temporaryDirectory -and (Test-Path -LiteralPath $temporaryDirectory)) { Remove-Item -LiteralPath $temporaryDirectory -Force }
  if ($localPreflightPassed) { Write-Output 'STAGE5_LOCAL_PREFLIGHT_PASS' }
}
