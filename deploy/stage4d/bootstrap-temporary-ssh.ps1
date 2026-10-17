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

  [Parameter()]
  [string]$TemporaryRoot = '',

  [Parameter()]
  [string]$SshCommand = '',

  [Parameter()]
  [string]$SshKeygenCommand = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-NativeCommand([string]$Candidate, [string]$DefaultName) {
  if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
    return (Resolve-Path -LiteralPath $Candidate).Path
  }
  return (Get-Command $DefaultName -CommandType Application -ErrorAction Stop).Source
}

if ($VmHost -notmatch '^[A-Za-z0-9.-]+$' -or $SshUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'VmHost or SshUser contains unsupported characters'
}

$ssh = Resolve-NativeCommand $SshCommand 'ssh'
$sshKeygen = Resolve-NativeCommand $SshKeygenCommand 'ssh-keygen'
if ([string]::IsNullOrWhiteSpace($TemporaryRoot)) {
  $TemporaryRoot = [IO.Path]::GetTempPath()
}
$resolvedTemporaryRoot = (Resolve-Path -LiteralPath $TemporaryRoot).Path
$keyDirectory = Join-Path $resolvedTemporaryRoot ('volunteerflow-stage4d-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $keyDirectory -ErrorAction Stop | Out-Null
$identityFile = Join-Path $keyDirectory 'id_ed25519'
$comment = 'volunteerflow-stage4d-' + [Guid]::NewGuid().ToString('D').ToLowerInvariant()

& $sshKeygen -q -t ed25519 -N '' -C $comment -f $identityFile
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $identityFile -PathType Leaf) -or
    -not (Test-Path -LiteralPath ($identityFile + '.pub') -PathType Leaf)) {
  throw 'Temporary Ed25519 key generation failed'
}

$publicKeyLine = ([IO.File]::ReadAllText($identityFile + '.pub', [Text.Encoding]::UTF8)).Trim()
if ($publicKeyLine -notmatch ('^ssh-ed25519 [A-Za-z0-9+/=]+ ' + [Regex]::Escape($comment) + '$')) {
  throw 'Generated public key did not contain the exact unique comment'
}

$installer = Join-Path $keyDirectory 'install-public-key.sh'
$installerBody = @"
set -eu
umask 077
mkdir -p ~/.ssh
touch ~/.ssh/authorized_keys
key='$publicKeyLine'
grep -qxF -- "`$key" ~/.ssh/authorized_keys || printf '%s\n' "`$key" >> ~/.ssh/authorized_keys
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
grep -qxF -- "`$key" ~/.ssh/authorized_keys
"@
[IO.File]::WriteAllText($installer, $installerBody, [Text.UTF8Encoding]::new($false))

$target = "$SshUser@$VmHost"
$installArguments = @('-p', [string]$SshPort, '-o', 'StrictHostKeyChecking=accept-new', $target, 'sh -s')
$installProcess = Start-Process `
  -FilePath $ssh `
  -ArgumentList $installArguments `
  -RedirectStandardInput $installer `
  -Wait `
  -PassThru
if ($installProcess.ExitCode -ne 0) {
  throw 'Visible SSH password session did not install the temporary public key'
}

& $ssh `
  -p $SshPort `
  -o BatchMode=yes `
  -o IdentitiesOnly=yes `
  -i $identityFile `
  $target `
  'true'
if ($LASTEXITCODE -ne 0) {
  throw 'Temporary key login verification failed'
}

Remove-Item -LiteralPath $installer -Force
Write-Output "IdentityFile=$identityFile"
Write-Output "PublicKeyComment=$comment"
