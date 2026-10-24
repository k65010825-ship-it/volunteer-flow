[CmdletBinding()]
param([string]$ConfigPath = '', [string]$MavenRepository = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $repository 'backend/config/application-local.yml' }
if ([string]::IsNullOrWhiteSpace($MavenRepository)) { $MavenRepository = Join-Path $env:USERPROFILE '.m2/repository' }
$artifacts = @(
  'org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar',
  'com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar',
  'org/apache/kafka/kafka-clients/3.9.2/kafka-clients-3.9.2.jar',
  'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar'
)
$dependencies = @($artifacts | ForEach-Object {
  $dependency = Join-Path $MavenRepository $_
  if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) { throw ('Missing probe dependency: ' + $_) }
  $dependency
})
# No Spring context, Maven, migration, consumer subscription, or production; logs contain fixed categories only.
& java --class-path ($dependencies -join [IO.Path]::PathSeparator) (Join-Path $PSScriptRoot 'LiveBackendPreflight.java') (Resolve-Path -LiteralPath $ConfigPath).Path
if ($LASTEXITCODE -ne 0) { throw 'Read-only probe process failed; do not interpret this as an environment pass' }
