# Task 5 approved-review minor fix report

## Scope

Fixed the stack launcher's PowerShell 7 native-command behavior without invoking Docker or reading live deployment credentials. When a caller enables `$PSNativeCommandUseErrorActionPreference`, a non-zero Docker result no longer becomes a terminating `NativeCommandExitException` before the launcher can preserve the exact exit code.

## TDD evidence

- RED first: a real PowerShell 7 process enabled `$PSNativeCommandUseErrorActionPreference`, invoked a stub `docker.cmd` that exited `23`, and observed launcher exit `1` with `NativeCommandExitException` instead of `23`.
- GREEN after the fix: the same executable contract receives exit `23`, confirms the caller's native-command preference is restored, and confirms no fixture secrets are printed.
- The existing Windows PowerShell 5.1 success/failure launcher contract remains green.

## Implementation

- `deploy/run-stage3-stack.ps1` detects whether the PowerShell 7 preference variable exists, temporarily disables only native-command error promotion around the Docker invocation, captures `$LASTEXITCODE`, and restores the original preference in `finally`.
- `$ErrorActionPreference = 'Stop'` remains unchanged, so cmdlet failures remain terminating.
- The code remains Windows PowerShell 5.1 compatible because it never directly reads the PowerShell 7-only variable unless `Get-Variable` finds it.

## Fresh verification

- Focused deployment contracts: 22 tests, 0 failures, 0 errors, 0 skipped.
- Backend `mvnw.cmd clean verify`: 605 tests, 0 failures, 0 errors, 0 skipped; repackaged JAR succeeded.
- PowerShell parser: 0 errors across the four Stage 3 module/scripts under both Windows PowerShell 5.1 and PowerShell 7.
- No Docker command, live environment file, VM endpoint, or deployment secret was used.
- The three user-owned frontend files remained outside this fix and retained their pre-fix SHA-256 hashes.
