# VolunteerFlow Stage 4D Live Deployment and Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy VolunteerFlow as two Spring Boot instances behind Nginx on the real VM, exercise shared Redis and Redis-outage behavior with k6, and preserve reproducible correctness and performance evidence without modifying existing business data.

**Architecture:** A dedicated Stage 4D Compose project declares stable `backend-a` and `backend-b` services, one Nginx public entry, and tool-profile services for the Java fixture and k6. A UUID-scoped fixture seeds two independent 100-member/20-seat datasets, issues short-lived JWT files, verifies MySQL facts, and cleans only its own rows. A fail-fast PowerShell/SSH workflow uploads a committed source archive, runs the VM scenarios, copies evidence locally, restores Redis connectivity, removes the test stack and temporary SSH key, and then produces a tracked, truthful report.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 8, Redis, Docker Compose, Nginx, PowerShell, POSIX shell, k6, JUnit 5, AssertJ, SnakeYAML

**Spec:** `docs/superpowers/specs/2026-10-17-stage-4d-live-deployment-and-load-test-design.md`

## Global Constraints

- Target `root@192.168.40.144:22`; authentication uses a temporary SSH key bootstrapped through one visible password prompt, never a password in a script.
- Preserve MySQL `192.168.40.144:3307` and Redis `192.168.40.144:6379`; do not stop, delete, recreate, or reconfigure their existing containers.
- Keep `SPRING_FLYWAY_ENABLED=false`; never rewrite Flyway history or use a schema reset as test setup.
- Use an isolated Compose project and `/opt/volunteerflow-stage4d/<runId>`; do not overwrite an existing deployment directory or container.
- All destructive SQL must be limited by exact IDs rediscovered from the canonical lowercase UUID run prefix; no timestamp-range or whole-table deletion.
- Secrets, JWT token files, real `.env`, raw k6 streams, full logs, and private keys must remain ignored and untracked.
- Normal and outage registration scenarios each use 100 unique members competing for a separate 20-seat `FIRST_COME` position.
- Correctness thresholds are hard gates; latency and throughput are measured facts, not invented SLA claims.
- The same-host k6 result is described only as a single-VM baseline.

## Review Focus

- A malformed, uppercase, empty, or path-like run ID must fail before any SQL, filesystem, SSH, or Docker operation; Task 1 pins this.
- A partial seed or failed k6 run must still be discoverable and exactly cleanable without touching unrelated users or organizations; Task 2 pins this.
- One backend becoming unhealthy must not make Nginx silently report a successful two-instance validation; Tasks 3 and 5 pin per-instance readiness and upstream evidence.
- Expected `401`, `429`, and `503` responses must be classified by scenario and never counted as generic success or hidden as transport failures; Task 4 pins this.
- Redis outage injection or a failed restore must never proceed to cleanup as though recovery passed; Tasks 5 and 7 pin fail-fast recovery and retained evidence.

---

### Task 1: Define the Stage 4D fixture identity and artifact contract

**Files:**
- Create: `backend/src/test/java/com/volunteerflow/Stage4dFixtureIdentity.java`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dFixtureIdentityTest.java`

**Interfaces:**
- Consumes: canonical lowercase UUID supplied by `stage4d.fixture.run-id`; dataset supplied by `stage4d.fixture.dataset`.
- Produces: `Stage4dFixtureIdentity.parse(String runId, String dataset)`, `prefix()`, `username(int memberIndex)`, `artifactName(String kind)`, and enum `Dataset { NORMAL, OUTAGE }`.

- [ ] **Step 1: Write the failing identity tests**

  Add tests named `acceptsCanonicalUuidAndKnownDataset`, `rejectsMalformedOrUppercaseRunIds`, `rejectsUnknownDatasets`, `buildsBoundedDeterministicNames`, and `artifactNamesCannotEscapeOutputDirectory`. Assert the exact prefixes `s4d-<uuid>-normal` and `s4d-<uuid>-outage`, member indexes `1..100`, and rejection of `/`, `\\`, `..`, control characters, index `0`, and index `101`.

- [ ] **Step 2: Run the focused test and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dFixtureIdentityTest test`

  Expected: FAIL because `Stage4dFixtureIdentity` does not exist.

- [ ] **Step 3: Implement the immutable identity type**

  Implement `Stage4dFixtureIdentity` as a package-private record or final class. `parse` must compare `UUID.fromString(value).toString()` with the unmodified input and accept only lowercase `normal` or `outage`. Generated usernames and artifact names must be derived internally rather than accepting filename fragments from callers.

- [ ] **Step 4: Run the focused test and verify green**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dFixtureIdentityTest test`

  Expected: all identity tests PASS.

- [ ] **Step 5: Commit the identity contract**

  ```powershell
  git add backend/src/test/java/com/volunteerflow/Stage4dFixtureIdentity.java backend/src/test/java/com/volunteerflow/Stage4dFixtureIdentityTest.java
  git commit -m "test: define stage 4d fixture identity"
  ```

### Task 2: Build the exact seed, verification, snapshot, and cleanup fixture

**Files:**
- Create: `backend/src/test/java/com/volunteerflow/Stage4dLoadFixtureIT.java`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dLoadFixtureContractTest.java`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dFixtureManifest.java`

**Interfaces:**
- Consumes: `Stage4dFixtureIdentity`; system properties `stage4d.fixture.action`, `stage4d.fixture.run-id`, `stage4d.fixture.dataset`, and `stage4d.fixture.output-dir`; application DB and JWT environment.
- Produces: actions `snapshot`, `seed`, `verify`, `cleanup`, and `assert-snapshot`; non-secret `manifest-<dataset>.json`; secret `tokens-<dataset>.json`; `verification-<dataset>.json`; `baseline.json`.

- [ ] **Step 1: Write failing fixture contract tests**

  Add tests for the exact action allowlist, required dataset rules, an output directory that must already exist, token artifacts with exactly 100 distinct nonblank tokens, a non-secret manifest containing exact organization/activity/position/member IDs, and verification expectations of 20 `CONFIRMED`, 80 `WAITLISTED`, waitlist sequence set `1..80`, 100 unique user IDs, and next waitlist sequence `81`. Assert the manifest never contains an access token and the token file never contains a password.

- [ ] **Step 2: Add cleanup and snapshot safety tests before implementation**

  Add source/behavior contracts asserting that cleanup first rediscovers IDs through exact organization name and exact generated usernames, deletes child tables in an explicit child-first allowlist, rejects more than one matching organization, asserts zero remaining run rows, and never contains `TRUNCATE`, `DROP`, a time-range predicate, or an unqualified `DELETE`. Assert `snapshot` and `assert-snapshot` hash canonical row data for pre-existing rows while excluding only exact Stage 4D identities.

- [ ] **Step 3: Run the focused tests and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dLoadFixtureContractTest test`

  Expected: FAIL because the fixture and manifest are absent.

- [ ] **Step 4: Implement manifest serialization and baseline hashing**

  Implement `Stage4dFixtureManifest` with exact fields `runId`, `dataset`, `organizationId`, `activityId`, `positionId`, and ordered `members(userId, username)`. Write the non-secret manifest and separate ordered `tokens(userId, accessToken, expiresAt)` JSON with Jackson to internally generated artifact names. For `baseline.json`, hash canonical, primary-key-ordered values from the project-owned tables with SHA-256 without writing raw row values to artifacts.

- [ ] **Step 5: Implement `Stage4dLoadFixtureIT` actions**

  Follow the existing `Stage3E2eFixtureIT` application-service pattern. Disable bootstrap admin and mock scheduled finalization/expiry jobs in the fixture context. Seed one isolated organization, a published activity, one capacity-20 `FIRST_COME` position, and 100 active member users. Use `AccessTokenIssuer.issue(AppUser)` for short-lived tokens; do not create refresh sessions or a test-only HTTP endpoint.

- [ ] **Step 6: Implement database-fact verification and exact cleanup**

  `verify` must read persisted rows and fail unless every hard invariant is exact. `cleanup` must tolerate a partially completed seed by rediscovering only exact run identities, delete the explicit organization graph child-first, remove fixture refresh sessions if any, remove exact users, and assert zero matching rows. `assert-snapshot` must compare the post-cleanup pre-existing-data fingerprint with `baseline.json`.

- [ ] **Step 7: Run fixture contract tests and the existing Stage 3 fixture regression**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dFixtureIdentityTest,Stage4dLoadFixtureContractTest,Stage3E2eFixtureContractTest test`

  Expected: all selected tests PASS without connecting to the VM database.

- [ ] **Step 8: Commit the fixture**

  ```powershell
  git add backend/src/test/java/com/volunteerflow/Stage4dFixtureManifest.java backend/src/test/java/com/volunteerflow/Stage4dLoadFixtureIT.java backend/src/test/java/com/volunteerflow/Stage4dLoadFixtureContractTest.java
  git commit -m "test: add isolated stage 4d load fixture"
  ```

### Task 3: Add the isolated two-backend Compose stack and observable Nginx routing

**Files:**
- Create: `deploy/stage4d/docker-compose.yml`
- Create: `deploy/stage4d/nginx.conf`
- Create: `deploy/stage4d/.env.example`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dDeploymentContractTest.java`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: the existing backend and frontend Dockerfiles plus the same DB, JWT, Redis, dynamic-check-in, and rate-limit environment names used by `deploy/docker-compose.yml`.
- Produces: services `backend-a`, `backend-b`, `frontend-nginx`, profile-gated `fixture`, and profile-gated `k6`; public port `${STAGE4D_APP_PORT:-8088}`; Nginx log field `upstream_addr`.

- [ ] **Step 1: Write the failing Compose/Nginx contract tests**

  Parse the new YAML with SnakeYAML. Assert two stable backend services share one YAML extension block, expose no host ports, use the same required environment map, and have independent readiness health checks. Assert Nginx depends on both healthy backends, publishes only port 80, and fixture/k6 services are behind the `tools` profile.

- [ ] **Step 2: Add security and observability assertions**

  Assert Nginx defines an upstream containing exactly `backend-a:8080` and `backend-b:8080`, overwrites `X-Forwarded-For` with `$remote_addr`, logs `$upstream_addr`, `$request_time`, and `$upstream_response_time`, and never logs Authorization, Cookie, or request bodies. Assert `.env.example` contains placeholders rather than `192.168.*`, and `.gitignore` excludes `output/stage4d/`, `deploy/stage4d/.env`, token JSON, raw k6 output, and private keys.

- [ ] **Step 3: Run the focused deployment test and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dDeploymentContractTest test`

  Expected: FAIL because Stage 4D deployment assets are absent.

- [ ] **Step 4: Implement the Compose stack**

  Use YAML anchors for the common backend definition and preserve all Stage 4C environment variables. Build `fixture` from the backend Dockerfile `build` target and mount only the run output directory when invoked. Pin the k6 image to `grafana/k6:0.54.0`; mount the read-only k6 scripts and per-run artifact directory only for tool invocations.

- [ ] **Step 5: Implement Nginx routing and example configuration**

  Configure round-robin upstream routing, the existing `/api`, `/actuator`, and SPA behavior, and the safe access-log format. The example environment documents `SPRING_FLYWAY_ENABLED=false` as specific to the restored database and leaves every secret as a placeholder.

- [ ] **Step 6: Run deployment contracts and existing Compose regressions**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dDeploymentContractTest,DeploymentComposeContractTest,Stage3StackLauncherContractTest test`

  Expected: all selected tests PASS and Stage 3 assets remain unchanged.

- [ ] **Step 7: Commit deployment assets**

  ```powershell
  git add .gitignore deploy/stage4d backend/src/test/java/com/volunteerflow/Stage4dDeploymentContractTest.java
  git commit -m "feat: add stage 4d dual backend stack"
  ```

### Task 4: Implement k6 scenarios with explicit business result classification

**Files:**
- Create: `loadtest/stage4d/lib/config.js`
- Create: `loadtest/stage4d/lib/http.js`
- Create: `loadtest/stage4d/rate-limit.js`
- Create: `loadtest/stage4d/registration.js`
- Create: `loadtest/stage4d/recovery.js`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dK6ContractTest.java`

**Interfaces:**
- Consumes: `BASE_URL`, `RUN_ID`, `DATASET`, `MANIFEST_PATH`, `TOKENS_PATH`, and scenario-specific expected limits.
- Produces: k6 custom metrics `business_checks_failed`, `unexpected_status`, `rate_limit_allowed`, `rate_limit_rejected`, and handle-summary JSON files under the run output directory.

- [ ] **Step 1: Write failing static contracts for shared configuration and secret safety**

  Assert every scenario imports `lib/config.js`, requires `BASE_URL` and a canonical `RUN_ID`, uses no embedded host, token, username, or password, and never prints Authorization headers or token bodies. Assert tokens are read only through `TOKENS_PATH`, match manifest user IDs exactly, and are not copied into summaries.

- [ ] **Step 2: Write failing scenario contracts**

  Assert `registration.js` creates 100 VUs, uses a start barrier, maps one unique manifest member to each VU, sends exactly one `POST /api/v1/activities/{activityId}/registrations`, and accepts only `201`. Assert `rate-limit.js` separately counts expected authentication rejection and `429` with `Retry-After`. Assert `recovery.js` checks readiness, a public activity read, and an authenticated registration-state read.

- [ ] **Step 3: Run the focused k6 contract and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dK6ContractTest test`

  Expected: FAIL because the scripts do not exist.

- [ ] **Step 4: Implement the shared config and HTTP helpers**

  Expose `requiredEnv(name)`, `loadManifest(path, runId, dataset)`, `loadTokens(path, manifest)`, `authHeaders(token)`, and `writeSummary(data, scenario)`. Reject mismatched run IDs/datasets, mismatched token user IDs, and non-HTTP(S) base URLs before the test begins.

- [ ] **Step 5: Implement the three k6 scenarios**

  Use scenario-specific checks rather than `http_req_failed` to interpret expected `401`, `429`, or `503`. Registration correctness remains a hard threshold with zero unexpected responses; latency trend metrics are exported without an invented pass/fail SLA.

- [ ] **Step 6: Run the contract tests**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dK6ContractTest test`

  Expected: all k6 asset contracts PASS.

- [ ] **Step 7: Commit the load scripts**

  ```powershell
  git add loadtest/stage4d backend/src/test/java/com/volunteerflow/Stage4dK6ContractTest.java
  git commit -m "test: add stage 4d k6 scenarios"
  ```

### Task 5: Build fail-fast SSH, remote execution, evidence, and key-lifecycle automation

**Files:**
- Create: `deploy/stage4d/bootstrap-temporary-ssh.ps1`
- Create: `deploy/stage4d/run-stage4d.ps1`
- Create: `deploy/stage4d/remote-stage4d.sh`
- Create: `deploy/stage4d/remove-temporary-ssh.ps1`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dRunnerContractTest.java`

**Interfaces:**
- Consumes: `-VmHost`, `-SshPort`, `-SshUser`, `-IdentityFile`, `-ComposeEnvFile`, committed Git archive, and the fixture/k6 interfaces from Tasks 2–4.
- Produces: `output/stage4d/<runId>/` containing preflight, Compose config, readiness, k6 summaries, fixture verification, Nginx upstream evidence, container logs, recovery results, cleanup results, and a machine-readable `run-status.json`.

- [ ] **Step 1: Write failing runner contracts for argument validation and no-secret output**

  Execute the PowerShell scripts against stub `ssh`, `scp`, and `git` commands. Assert canonical UUID generation, explicit identity-file use, exact remote root `/opt/volunteerflow-stage4d/<runId>`, unique Compose project naming, caller environment restoration, and failure before remote mutation when the env file or committed clean tree is missing. Seed stub secrets containing spaces, `$`, `#`, and quotes; assert none appear in stdout, stderr, command captures, or status JSON.

- [ ] **Step 2: Add failure-path contracts**

  Stub failures at preflight, backend readiness, rate-limit scenario, normal registration, outage registration, Redis restore, evidence download, fixture cleanup, and SSH-key removal. Assert the first test failure is preserved, recovery is attempted before business-data cleanup, cleanup status is reported independently, and a failed recovery cannot be reported as success.

- [ ] **Step 3: Add key-lifecycle contracts**

  Assert bootstrap creates an Ed25519 key in a newly created temporary directory, opens a visible password-prompting SSH process without putting a password in arguments, appends one exact uniquely commented public-key line, and verifies key login. Assert removal filters only the exact full public-key line, preserves a stubbed neighboring key, verifies the line is gone, and deletes the local private key only after remote removal succeeds.

- [ ] **Step 4: Run the focused runner test and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dRunnerContractTest test`

  Expected: FAIL because the runner scripts are absent.

- [ ] **Step 5: Implement temporary-key bootstrap and removal**

  Use Windows OpenSSH tools discovered with `Get-Command`. The bootstrap script may pass only public-key material through the visible SSH session. The removal script must use key authentication and an exact-line atomic rewrite of `authorized_keys`; it must not delete or replace the file wholesale.

- [ ] **Step 6: Implement local orchestration**

  `run-stage4d.ps1` verifies a clean committed `feat/stage-4d` tree, creates a Git archive rather than copying `.git` or ignored files, generates a fresh cryptographically random `RATE_LIMIT_KEY_SECRET` of at least 32 bytes without logging it, uploads the archive and protected run environment, invokes the remote runner, always attempts evidence download, and writes separate primary/recovery/cleanup/key-removal statuses. It must not automatically delete a failed run before its evidence is copied.

- [ ] **Step 7: Implement the remote runner**

  `remote-stage4d.sh` uses `set -Eeuo pipefail`, verifies Docker/Compose/disk/ports and external MySQL/Redis reachability, renders `docker compose config`, builds the isolated stack, runs snapshot/seed/scenarios/verify, injects outage only by recreating the Stage 4D backends with `REDIS_HOST=127.0.0.1` and `REDIS_PORT=6379`, restores the real Redis values before cleanup, and tears down only the run-scoped Compose project and directory after successful evidence staging.

- [ ] **Step 8: Run runner contracts**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dRunnerContractTest test`

  Expected: all stubbed success and failure paths PASS.

- [ ] **Step 9: Commit the runner**

  ```powershell
  git add deploy/stage4d backend/src/test/java/com/volunteerflow/Stage4dRunnerContractTest.java
  git commit -m "feat: automate stage 4d vm acceptance"
  ```

### Task 6: Document operation, interpretation, and result generation

**Files:**
- Create: `docs/operations/stage-4d-live-load-test.md`
- Create: `docs/reports/stage-4d-report-template.md`
- Create: `backend/src/test/java/com/volunteerflow/Stage4dDocumentationContractTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: exact commands and artifacts produced by Tasks 2–5.
- Produces: safe operator procedure and a report structure that distinguishes prepared source, live pass/fail evidence, and same-host performance limitations.

- [ ] **Step 1: Write the failing documentation contract**

  Assert the operation guide includes prerequisites, visible one-time password prompt, preflight, start, each scenario, recovery, evidence copy, fixture cleanup, stack removal, exact SSH-key removal, rerun behavior, and manual recovery commands keyed by `runId`. Assert it says not to stop existing MySQL/Redis and keeps Flyway disabled for this restored database.

- [ ] **Step 2: Add truthfulness assertions for README and report template**

  Assert the template has fields for commit, run ID, VM resources, Docker versions, both upstreams, database facts, request count, throughput, p50/p90/p95/p99/max, errors, recovery, cleanup, and limitations. Before the live run, README must say Stage 4D is prepared but not passed; it must not contain fabricated metric values.

- [ ] **Step 3: Run the documentation contract and verify red**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dDocumentationContractTest test`

  Expected: FAIL because documentation is absent.

- [ ] **Step 4: Write the guide, template, and pre-run README state**

  Keep commands copyable but use placeholders for secrets. State explicitly that the normal Redis test uses the existing service, outage injection uses an unreachable address without stopping it, and same-host k6 numbers are not production capacity.

- [ ] **Step 5: Run documentation and deployment contracts**

  Run: `cd backend && .\mvnw.cmd -Dtest=Stage4dDocumentationContractTest,Stage4dDeploymentContractTest,Stage4dRunnerContractTest test`

  Expected: all selected tests PASS.

- [ ] **Step 6: Commit operational documentation**

  ```powershell
  git add README.md docs/operations/stage-4d-live-load-test.md docs/reports/stage-4d-report-template.md backend/src/test/java/com/volunteerflow/Stage4dDocumentationContractTest.java
  git commit -m "docs: add stage 4d live acceptance runbook"
  ```

### Task 7: Pass local gates, bootstrap the key, and execute the real VM acceptance

**Files:**
- Runtime output only: `output/stage4d/<runId>/` (ignored)
- Remote temporary path only: `/opt/volunteerflow-stage4d/<runId>`

**Interfaces:**
- Consumes: the committed Stage 4D branch and a user-completed visible root password prompt.
- Produces: one complete real run or a preserved partial run with an exact failed gate and recovery state.

- [ ] **Step 1: Run all local source gates from a clean commit**

  Run:

  ```powershell
  cd backend
  .\mvnw.cmd test
  cd ..\frontend
  npm run test
  npm run build
  ```

  Expected: backend, frontend tests, TypeScript, and production build all PASS. Record exact counts rather than copying historical counts.

- [ ] **Step 2: Recheck repository and secret boundaries**

  Run `git status --short --branch`, `git diff --check`, and a tracked-file scan for the real VM address only where the project intentionally keeps defaults. Verify no `.env`, key, token JSON, or raw output is tracked.

- [ ] **Step 3: Bootstrap the temporary SSH key**

  Run `deploy/stage4d/bootstrap-temporary-ssh.ps1` with `root`, host `192.168.40.144`, and port `22`. The user enters the password only in the visible SSH terminal. Verify subsequent `ssh -i <temporary-key> root@192.168.40.144 true` succeeds without a password.

- [ ] **Step 4: Perform read-only VM preflight before upload**

  Capture OS, CPU, memory, disk, Docker, Compose, current containers, listening ports, MySQL container identity, Redis container identity, DB schema/Flyway facts, and external-service reachability. Stop if the discovered state contradicts the spec; do not repair it implicitly.

- [ ] **Step 5: Execute the full Stage 4D runner**

  Run `deploy/stage4d/run-stage4d.ps1` with the temporary identity and untracked Stage 4D env file. Require all gates in order: config render, image build, dual readiness, both upstreams, shared rate limit, normal seed/run/verify, outage seed/run/verify, Redis restore, recovery smoke, cleanup, baseline comparison, evidence download, and stack teardown.

- [ ] **Step 6: Verify cleanup and key removal independently**

  Confirm no Stage 4D Compose containers/network remain, the remote run directory is removed only after evidence is local, both fixture datasets contain zero rows, the baseline matches, the exact temporary public key is absent, and the local temporary key directory is gone. If any check fails, preserve the run ID and execute only the documented exact recovery command.

### Task 8: Publish the measured report and final verification

**Files:**
- Create: `docs/reports/2026-10-17-stage-4d-live-load-test.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: verified artifacts from `output/stage4d/<runId>/`; no metric may be copied from the template or inferred from source tests.
- Produces: the final tracked Stage 4D evidence summary and an accurate README status.

- [ ] **Step 1: Cross-check machine-readable evidence before writing prose**

  Compare k6 summaries, fixture verification JSON, Nginx upstream counts, recovery status, cleanup status, baseline comparison, commit, and run ID. If any hard gate failed, report Stage 4D as partial/failed and retain the exact failure instead of marking complete.

- [ ] **Step 2: Write the measured report**

  Fill every report field from artifacts. Include 20/80 database facts for both registration scenarios, the exact shared-limit result, measured request/latency/throughput/error metrics, VM resource context, both upstream counts, recovery and cleanup results, and the same-host limitation.

- [ ] **Step 3: Update README according to the actual outcome**

  Mark only gates supported by this run. Link the report and keep any failed or unexecuted item visibly pending.

- [ ] **Step 4: Run the final local verification suite**

  Run:

  ```powershell
  cd backend
  .\mvnw.cmd test
  cd ..\frontend
  npm run test
  npm run build
  git diff --check
  git status --short --branch
  ```

  Expected: all source gates PASS; only the intended report/README changes remain before commit; ignored raw evidence remains untracked.

- [ ] **Step 5: Commit the truthful result report**

  ```powershell
  git add README.md docs/reports/2026-10-17-stage-4d-live-load-test.md
  git commit -m "docs: record stage 4d live load results"
  ```

- [ ] **Step 6: Perform final branch audit**

  Verify a clean `feat/stage-4d` worktree, review the diff from `8f676ea`, and confirm no secret, private key, token artifact, real `.env`, raw k6 stream, or full container log is present in any Stage 4D commit.
