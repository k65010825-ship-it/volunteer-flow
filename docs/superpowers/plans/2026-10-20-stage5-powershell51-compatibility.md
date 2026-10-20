# Stage 5 Windows PowerShell 5.1 Compatibility Corrective Plan

## Context

The reviewed Stage 5 acceptance runner stopped locally before any SSH deployment because Windows PowerShell 5.1 bound `$line.Split(@('='), 2)` to the wrong overload. No backup, migration, fixture, or container action occurred, and the temporary SSH key was removed successfully.

This is a new corrective task, separate from the completed five Task 9A fix rounds. It must not broaden live authorization or weaken any existing safety gate.

## Task 1: Make the local runner verifiably compatible with Windows PowerShell 5.1

**Files:**
- Modify `deploy/stage5/run-stage5.ps1`.
- Modify `deploy/lib/native-tools.psm1` only if an actual 5.1 incompatibility is reproduced.
- Create `deploy/stage5/test-powershell51-compatibility.ps1`.
- Modify `backend/src/test/java/com/volunteerflow/Stage5RunnerContractTest.java`.
- Update `docs/operations/stage-5-kafka-outbox.md` if invocation or prerequisites change.

**Requirements:**
- Parse dotenv entries at the first literal `=` using APIs whose overload behavior is identical in Windows PowerShell 5.1 and PowerShell 7; preserve additional `=` characters in the value.
- Audit the entire local runner and imported native-tool module for PowerShell 7-only syntax, .NET-only APIs, encoding differences, array-to-string coercion, and process APIs.
- Add a non-mutating `-LocalPreflightOnly` mode. It must execute all local gates needed before SSH: live-authorization/root/target/runId validation, identity/public-key format, strict env parsing, deterministic tool resolution/version checks, branch/clean commit verification, git archive creation, and exact driver hashing. It must use an isolated validated temporary directory, remove it in `finally`, create no `deploy/stage5/output` directory, make no network/SSH call, remove no key, and print no environment values/secrets.
- `-LocalPreflightOnly` must refuse `-MigrationReviewed`, never weaken the normal live path, and emit a fixed success category only after every local gate passes.
- JSON/text evidence written by the normal runner must have explicit stable UTF-8 behavior across PowerShell 5.1/7.
- The compatibility test must invoke actual `powershell.exe -Version 5.1` on Windows, not merely parse source with `pwsh`. It must cover values containing an additional `=`, CRLF, UTF-8 without BOM and BOM input as approved, multiple tool candidates, cleanup on failure, no network process creation, and no secret disclosure.

**TDD and verification:**
1. Reproduce the exact overload failure under `powershell.exe` before production edits.
2. Add failing tracked compatibility tests.
3. Implement the minimal compatible parser/preflight refactor.
4. Run the actual PowerShell 5.1 offline suite, Stage 5 focused contracts, all existing offline driver/runtime/native suites, syntax/secret/diff checks, and the full Java 17 backend suite.
5. Commit with `fix(stage5): support Windows PowerShell 5.1 runner` and obtain an independent review.

## Task 2: Resume preparation and live acceptance

Only after Task 1 review is clean:

1. Create one fresh temporary SSH key.
2. Run `-LocalPreflightOnly` with the real private configuration and clean committed worktree.
3. Run preparation without `-MigrationReviewed`; require remote prerequisite, backup, and fingerprint evidence, then stop at the migration gate.
4. Review the backup/fingerprint and V10 hash before a new key/run with `-MigrationReviewed`.
5. Execute the existing run-scoped Kafka/backend acceptance flow, cleanup, evidence download, key removal, and measured documentation gates.

The existing MySQL/Redis containers must never be stopped or replaced, and `SPRING_FLYWAY_ENABLED=false` remains mandatory.
