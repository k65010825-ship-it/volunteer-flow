# Task 2 Redis client bridge fix round 1 report

## Scope and evidence boundary

This review correction was implemented and verified locally from base `351b5acf0c97af31f11288c53307066bd98c6291`. No SSH, network, VM, Docker daemon, container, database, Redis service, or live acceptance action was performed. The results below are offline behavior evidence only.

## Review judgment

- **IMP-01 confirmed.** Preflight inspected the configured Redis name but the broker later executed by that mutable name. A name replacement between those actions could receive `REDISCLI_AUTH` before the final identity comparison detected drift.
- **MIN-01 confirmed.** Empty or arbitrary successful Redis version output was accepted as evidence and PING followed.
- **MIN-02 confirmed.** The claimed isolated test PATH still included `/usr/bin:/bin`, so host `redis-cli` absence depended on the runner.

## TDD RED

Production code was unchanged when the new behavior tests first ran.

- Wrong returned name, a short Redis ID, and duplicate MySQL/Redis IDs all reached a Redis client call; the successful path did not carry the inspected full ID.
- Empty, arbitrary, and multiline Redis version output proceeded to PING.
- The real framed bridge rejected the new full-ID logical request shape and therefore could not prove an immutable-ID Docker exec.
- The host-client fixture recorded an isolated PATH that still contained `/usr/bin:/bin`.
- The Bash evidence behavior test showed there was no validator. Its multiline value was then transported through an environment fixture rather than Windows command-line argv so the test exercises a real embedded newline.

## Minimal implementation

- Both inspect results must be exact objects with a complete 64-character lowercase hexadecimal ID, exact `/<configured-name>`, a bounded nonempty image, and `running=true`; the two IDs must differ.
- Preflight stores the approved identities before any Redis client call. Version, PING, SCAN, and cleanup DEL logical requests all carry the approved full Redis ID.
- The broker revalidates that full ID and constructs `docker exec --env REDISCLI_AUTH <full-id> redis-cli ...`. It never resolves the mutable container name for a credential-bearing exec. If the approved container disappears, exec by the old ID fails; a replacement using the old name does not receive the password.
- The existing Redis command allowlist, configured host/port/database, exact run namespace, 100-key DEL batch maximum, 1000-key cleanup maximum, TLS rejection, and lifecycle-verb rejection remain unchanged.
- Redis version evidence must be a bounded single-line `redis-cli <version>` value before PING. Bash version evidence must be bounded, recognizable, and free of control/newline characters before it is recorded.
- The no-host-client behavior test now uses a controlled PATH containing only explicit wrappers for required tools, asserts `redis-cli` is absent, and verifies the exact full ID in every fake-Docker exec.

## Fresh verification

- `python -B deploy/stage5/test_pipe_bridge.py`: 22/22 PASS. Windows cannot create the native leaf symlink without privilege (WinError 1314); retained hardlink/FIFO coverage passed.
- `python -B deploy/stage5/test_acceptance.py`: 28/28 PASS.
- `python -B deploy/stage5/test_runtime.py`: 14/14 PASS.
- `test-bash42-compatibility.sh`: 3/3 PASS on local Bash 5.3.15; this is not live Bash 4.2 evidence.
- `test-powershell51-compatibility.ps1`: actual Windows PowerShell 5.1 and PowerShell 7 each PASS with 220 assertions.
- `test-native-tools.ps1`: actual Windows PowerShell 5.1 and PowerShell 7 each PASS with 38 assertions.
- `bash -n deploy/stage5/pipe-bridge.sh` and `bash -n deploy/stage5/remote-stage5.sh`: PASS.
- Focused Java 17 contracts `Stage5DeploymentContractTest,Stage5RunnerContractTest`: PASS, zero failures.
- Full Java 17 default suite: 908 tests, zero failures/errors/skips.
- `git diff --check`: PASS before this report was added; it is rerun immediately before commit.

## Remaining live proof

This correction does not prove the current VM container identity, its client version, endpoint reachability, authentication, or namespace state. Those remain gates for a separately authorized preparation/live run. The Stage 5 Docker integration profile and full live acceptance were not run here.
