# Task 2 Redis client bridge fix round 2 report

## Scope and evidence boundary

This correction was implemented and verified locally from base `1f636391e9643ca1edcb75ef719870d323da46f4`. No SSH, network, VM, Docker daemon, container, database, Redis service, key, or live acceptance action was performed. These results are offline behavior evidence only.

## Review judgment

- **IMP-01 PARTIAL confirmed.** The protocol accepted any syntactically valid 64-character lowercase hexadecimal ID. The broker then exported `REDISCLI_AUTH` and executed that ID without independently binding it to trusted `STAGE5_REDIS_CONTAINER` state.
- **MIN-01 PARTIAL confirmed.** Redis version output was normalized with `.strip()` before length and shape validation, so blank extra lines, edge controls, and arbitrarily long surrounding whitespace could be hidden before evidence validation.
- The round-1 Bash version validator already checked raw length, newline/control characters, and format. Round 2 adds explicit LF/CR/tab/oversized regression cases but requires no Bash production change.

## TDD RED

Production code was unchanged when the round-2 tests first ran.

- A request containing a different valid 64-hex ID produced `docker exec --env REDISCLI_AUTH <different-id> redis-cli --version`; no trusted identity lookup occurred first.
- Seven raw Redis-version counterexamples reached PING: a leading blank line, two terminal linefeeds, a leading tab, a trailing tab, a lone terminal carriage return, an edge C0 control, and more than 200 characters of surrounding spaces.
- The expanded Bash-version raw-input cases passed at the base commit, confirming that this part was already fail-closed rather than manufacturing a production change.

## Minimal implementation

- For every logical Redis operation, the host broker reads only the configured container name first and performs a fixed, read-only, at-most-15-second `docker inspect --format '{{.Id}}' <configured-name>`.
- The inspect result must be one canonical full ID exactly equal to the request ID. Only after equality succeeds does the broker read and export `REDIS_PASSWORD`, then execute `docker exec` by the immutable approved ID.
- An alternative canonical ID therefore causes only the uncredentialed inspect and fails before any credential-bearing exec. If the configured name is rebound after comparison, exec by the old immutable ID reaches only the old container or fails; it cannot redirect to the same-name replacement.
- No generic Docker operation was added to the protocol. Existing Redis verb, endpoint, namespace, batch, TLS, lifecycle, and output bounds remain intact.
- Redis version raw text is bounded to 202 characters before normalization and rejects C0/DEL controls. It permits no line ending, one LF, or one CRLF only. After removing that one permitted terminator, the content must be one line, have no edge whitespace, be at most 200 characters, and match the conservative `redis-cli <version>` shape.

## Fresh verification

- `python -B deploy/stage5/test_pipe_bridge.py`: 23/23 PASS. Windows native leaf-symlink creation remains unavailable without privilege (WinError 1314); retained hardlink/FIFO coverage passed.
- `python -B deploy/stage5/test_acceptance.py`: 28/28 PASS.
- `python -B deploy/stage5/test_runtime.py`: 14/14 PASS.
- Bash syntax checks for `pipe-bridge.sh` and `remote-stage5.sh`: PASS.
- `test-bash42-compatibility.sh`: 3/3 PASS on local Bash 5.3.15; this is not live Bash 4.2 evidence.
- `test-powershell51-compatibility.ps1`: actual Windows PowerShell 5.1 and PowerShell 7 each PASS with 220 assertions.
- `test-native-tools.ps1`: actual Windows PowerShell 5.1 and PowerShell 7 each PASS with 38 assertions.
- Focused Java 17 contracts `Stage5DeploymentContractTest,Stage5RunnerContractTest`: PASS, zero failures.
- Full Java 17 default suite: 908 tests, zero failures/errors/skips.
- `git diff --check`: PASS before this report was added; rerun immediately before commit.

## Remaining live proof

The correction does not prove current VM identity, Redis client compatibility, authentication, endpoint reachability, or namespace state. Those remain gates for a separately authorized preparation/live run. No Stage 5 Docker integration profile or live acceptance was run here.
