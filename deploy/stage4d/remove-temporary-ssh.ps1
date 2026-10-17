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

  [Parameter()]
  [string]$SshCommand = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($VmHost -notmatch '^[A-Za-z0-9.-]+$' -or $SshUser -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'VmHost or SshUser contains unsupported characters'
}
$resolvedIdentity = (Resolve-Path -LiteralPath $IdentityFile).Path
$publicKeyPath = $resolvedIdentity + '.pub'
if (-not (Test-Path -LiteralPath $publicKeyPath -PathType Leaf)) {
  throw 'The matching public key file is required for exact remote removal'
}
if ([string]::IsNullOrWhiteSpace($SshCommand)) {
  $ssh = (Get-Command ssh -CommandType Application -ErrorAction Stop).Source
} else {
  $ssh = (Resolve-Path -LiteralPath $SshCommand).Path
}

$publicKeyLine = ([IO.File]::ReadAllText($publicKeyPath, [Text.Encoding]::UTF8)).Trim()
if ($publicKeyLine -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+ volunteerflow-stage4d-[0-9a-f-]+$') {
  throw 'Refusing to remove a key without the exact Stage 4D public-key format'
}

$removalScript = Join-Path ([IO.Path]::GetTempPath()) ('remove-stage4d-key-' + [Guid]::NewGuid().ToString('N') + '.sh')
$scriptBody = @"
set -eu
umask 077
file=~/.ssh/authorized_keys
key='$publicKeyLine'
test -f "`$file"
tmp=`$(mktemp ~/.ssh/authorized_keys.stage4d.XXXXXX)
trap 'rm -f "`$tmp"' EXIT
grep -Fvx -- "`$key" "`$file" > "`$tmp" || true
chmod 600 "`$tmp"
mv "`$tmp" "`$file"
trap - EXIT
if grep -qxF -- "`$key" "`$file"; then
  exit 42
fi
"@
[IO.File]::WriteAllText($removalScript, $scriptBody, [Text.UTF8Encoding]::new($false))

try {
  $target = "$SshUser@$VmHost"
  Get-Content -LiteralPath $removalScript -Raw | & $ssh `
    -p $SshPort `
    -o BatchMode=yes `
    -o IdentitiesOnly=yes `
    -i $resolvedIdentity `
    $target `
    'sh -s'
  if ($LASTEXITCODE -ne 0) {
    throw 'Exact remote public-key removal or verification failed'
  }

  Remove-Item -LiteralPath $resolvedIdentity -Force
  Remove-Item -LiteralPath $publicKeyPath -Force
} finally {
  Remove-Item -LiteralPath $removalScript -Force -ErrorAction SilentlyContinue
}

Write-Output 'Temporary Stage 4D SSH key removed from the VM and local disk.'
