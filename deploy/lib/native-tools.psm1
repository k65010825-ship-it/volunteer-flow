Set-StrictMode -Version Latest

function Test-ApplicationPath([string]$Name, [string]$Path) {
  try {
    if (-not [IO.Path]::IsPathRooted($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.PSProvider.Name -ne 'FileSystem') { return $false }
    if ($env:OS -eq 'Windows_NT') {
      if ($item.Extension -ine '.exe' -or $item.Length -eq 0 -or $Path -match '[\\/]Microsoft[\\/]WindowsApps[\\/]') { return $false }
      return $item.BaseName -ieq $Name
    }
    return $item.Name -eq $Name -or ($Name -eq 'python' -and $item.Name -eq 'python3')
  } catch { return $false }
}

function Test-ApplicationVersion([string]$Name, [string]$Path) {
  $process = $null
  try {
    # These tools lack a common noninteractive --version switch; validate the
    # Windows OpenSSH binary's version metadata instead (not a signature check).
    if ($Name -in @('ssh','scp','ssh-keygen')) {
      $metadata = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
      return $metadata.ProductVersion -match '^OpenSSH_(\d+)\.' -and [int]$Matches[1] -ge 7
    }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Path
    $start.Arguments = '--version'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { return $false }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(10000)) { $process.Kill(); return $false }
    $output = ($stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()).Trim()
    if ($process.ExitCode -ne 0) { return $false }
    if ($Name -eq 'git') { return $output -match '^git version (\d+)\.' -and [int]$Matches[1] -ge 2 }
    if ($Name -eq 'python' -and $output -match '^Python (\d+)\.(\d+)\.(\d+)') {
      return [int]$Matches[1] -eq 3 -and [int]$Matches[2] -ge 8
    }
    return $false
  } catch { return $false }
  finally { if ($null -ne $process) { $process.Dispose() } }
}

function Select-ApprovedApplication {
  param(
    [ValidateSet('git','python','ssh','scp','ssh-keygen')][string]$Name,
    [AllowEmptyCollection()][object[]]$Candidates,
    [AllowEmptyCollection()][string[]]$PreferredPaths = @()
  )
  if ($Candidates.Count -gt 64) { throw "Too many application candidates for $Name" }
  $paths = [Collections.Generic.List[string]]::new()
  foreach ($candidate in $Candidates) {
    if ($candidate -isnot [Management.Automation.ApplicationInfo]) { throw "Unsafe non-application candidate for $Name" }
    # Read Source on ONE ApplicationInfo at a time; never project an array to string.
    $path = $candidate.Source
    if ($path -isnot [string]) { throw "Unsafe application source for $Name" }
    if (-not $paths.Contains($path)) { $paths.Add($path) }
  }
  $ordered = [Collections.Generic.List[string]]::new()
  foreach ($preferred in $PreferredPaths) {
    if ($paths.Contains($preferred) -and -not $ordered.Contains($preferred)) { $ordered.Add($preferred) }
  }
  foreach ($path in $paths) { if (-not $ordered.Contains($path)) { $ordered.Add($path) } }
  foreach ($path in $ordered) {
    if ((Test-ApplicationPath $Name $path) -and (Test-ApplicationVersion $Name $path)) {
      return [string](Get-Item -LiteralPath $path).FullName
    }
  }
  throw "No compatible application found for $Name"
}

function Resolve-ApprovedApplication {
  param(
    [ValidateSet('git','python','ssh','scp','ssh-keygen')][string]$Name,
    [string]$ExplicitPath = ''
  )
  if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
    try {
      $path = (Resolve-Path -LiteralPath $ExplicitPath -ErrorAction Stop).Path
      if (-not (Test-ApplicationPath $Name $path)) { throw 'Invalid explicit path' }
      $commands = @(Get-Command -Name $path -CommandType Application -ErrorAction Stop)
      return Select-ApprovedApplication -Name $Name -Candidates $commands -PreferredPaths @($path)
    } catch { throw "Invalid explicit application for $Name" }
  }
  # Deterministic order: approved Windows installation locations, then PATH order.
  # App-execution aliases, script shims and incompatible versions never qualify.
  $preferred = @()
  if ($env:OS -eq 'Windows_NT') {
    if ($Name -in @('ssh','scp','ssh-keygen')) {
      $preferred += Join-Path $env:SystemRoot "System32/OpenSSH/$Name.exe"
    } elseif ($Name -eq 'git') {
      $preferred += Join-Path $env:ProgramFiles 'Git/cmd/git.exe'
      $preferred += Join-Path $env:ProgramFiles 'Git/bin/git.exe'
    }
  }
  $commands = @()
  foreach ($path in $preferred) {
    if (Test-Path -LiteralPath $path -PathType Leaf) { $commands += @(Get-Command -Name $path -CommandType Application -ErrorAction SilentlyContinue) }
  }
  $commands += @(Get-Command -Name $Name -CommandType Application -All -ErrorAction SilentlyContinue)
  return Select-ApprovedApplication -Name $Name -Candidates $commands -PreferredPaths $preferred
}

Export-ModuleMember -Function Resolve-ApprovedApplication,Select-ApprovedApplication
