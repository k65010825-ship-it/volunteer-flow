# Stage 4B Dynamic Check-in QR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Redis-backed, short-lived rotating QR credential that lets confirmed members self-check in while MySQL remains the attendance source of truth.

**Architecture:** A Redis token store issues one opaque token per session and 30-second UTC window, expiring at window start plus 45 seconds. A non-transactional dynamic-check-in service validates Redis before calling the existing transactional `CheckinService`, which rechecks proof expiry and MySQL session state after acquiring locks. The Vue manager page renders the QR locally, and the member page reads the secret from a URL fragment and clears it before submission.

**Tech Stack:** Java 17, Spring Boot 3, Spring Data Redis, MyBatis-Plus, MySQL, JUnit 5, Mockito, Vue 3, TypeScript, Vitest, `qrcode`

**Spec:** `docs/superpowers/specs/2026-10-15-stage-4b-dynamic-checkin-qr-design.md`

## Global Constraints

- Keep Java at version 17 and retain the existing Spring Boot 3 modular-monolith structure.
- Do not change the configured MySQL or Redis host, port, password, database, command timeout, or readiness behavior.
- Redis must never decide attendance uniqueness, capacity, registration status, or durable session state.
- Use a 30-second UTC window, an absolute expiry at window start plus 45 seconds, and 24 random bytes encoded with unpadded Base64URL.
- Keep omitted `sessionType` backward compatible by defaulting to `STATIC_CODE`; the updated management UI explicitly requests `DYNAMIC_QR`.
- Never log, audit, persist to MySQL, or include the dynamic token in `toString()` or error messages.
- Keep Redis I/O outside the MySQL check-in transaction and preserve the activity → session → membership → registration-cycle lock order.
- Redis failure disables only dynamic issue/verification; session close and manual check-in remain MySQL-backed.

## Review Focus

- A credential requested at the final second of its window expires at the same absolute boundary as one requested at the first second; Task 1 pins remaining TTL rather than resetting 45 seconds.
- An old client omitting `sessionType` still receives a static code; Task 2 pins backward compatibility.
- Redis verification failure never enters the transactional check-in service; Task 4 verifies zero MySQL service interaction.
- QR secrets never enter the query string or browser history after parsing; Task 6 tests fragment construction and removal.
- Concurrent or repeated dynamic submissions still create only one attendance record; Task 4 reuses and extends the database-conflict behavior tests.

---

### Task 1: Redis Dynamic Credential Foundation

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinTokenGenerator.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/SecureDynamicCheckinTokenGenerator.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinTokenStore.java`
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/.env.example`
- Test: `backend/src/test/java/com/volunteerflow/checkin/DynamicCheckinPropertiesTest.java`
- Test: `backend/src/test/java/com/volunteerflow/checkin/SecureDynamicCheckinTokenGeneratorTest.java`
- Test: `backend/src/test/java/com/volunteerflow/checkin/DynamicCheckinTokenStoreTest.java`

**Interfaces:**
- Consumes: `RedisKeyFactory.key(String module, String businessKey)` and `StringRedisTemplate`.
- Produces: `DynamicCheckinProperties(Duration window, Duration ttl, int tokenBytes)`; `DynamicCheckinTokenGenerator.generate(int bytes)`; `DynamicCheckinTokenStore.issue(long sessionId, Instant now)` returning `IssuedCredential(windowEpochSecond, expiresAt, token)`; `verify(long sessionId, long windowEpochSecond, String token, Instant now)` returning `VerifiedCredential(windowEpochSecond, expiresAt)`; and `evictRecent(long sessionId, Instant now)`.

- [ ] **Step 1: Write failing properties, generator, and store tests**

  Add tests named `rejectsTtlNotGreaterThanWindow`, `generatesUnpaddedBase64UrlFromConfiguredEntropy`, `sameWindowReturnsWinningToken`, `lateIssueUsesOnlyRemainingAbsoluteTtl`, `nextWindowRotatesToken`, `previousWindowWorksOnlyUntilAbsoluteExpiry`, `rejectsWrongAndCrossSessionTokens`, and `redisFailureIsReportedWithoutTokenText`. Assert the exact 30-second, 45-second, and 24-byte defaults and the key `volunteerflow:test:dynamic-checkin:101:<window>:v1`.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinPropertiesTest,SecureDynamicCheckinTokenGeneratorTest,DynamicCheckinTokenStoreTest" test`
  Expected: FAIL because the dynamic credential types do not exist.

- [ ] **Step 3: Implement the minimal Redis credential foundation**

  Bind `volunteerflow.checkin.dynamic-code`; calculate `windowEpochSecond = floor(epochSecond / windowSeconds) * windowSeconds`; calculate Redis expiry as `windowStart + ttl`; use `setIfAbsent(key, token, remainingDuration)` then read the winning value; compare decoded token bytes with `MessageDigest.isEqual`; reject blank, malformed, future, expired, or non-aligned windows without exposing which check failed.

- [ ] **Step 4: Run focused and Redis foundation tests and verify GREEN**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinPropertiesTest,SecureDynamicCheckinTokenGeneratorTest,DynamicCheckinTokenStoreTest,RedisFoundationTest" test`
  Expected: PASS with no live Redis requirement.

- [ ] **Step 5: Commit**

  ```bash
  git add backend/.env.example backend/src/main/resources/application.yml backend/src/main/java/com/volunteerflow/checkin backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java backend/src/test/java/com/volunteerflow/checkin
  git commit -m "feat: add dynamic check-in token store"
  ```

### Task 2: Dynamic Session Creation with Static Compatibility

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinController.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinSessionServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java`

**Interfaces:**
- Consumes: existing activity-first locking, RBAC, audit, notifications, and static `CheckinCodeGenerator`.
- Produces: `OpenSessionRequest(Integer durationMinutes, String sessionType)`; `OpenedSession(..., String sessionType, String code)` with null code for dynamic sessions; a mapper insert path that leaves `static_code_hash` null for `DYNAMIC_QR`.

- [ ] **Step 1: Write failing session compatibility tests**

  Add tests `omittedSessionTypeStillCreatesStaticSession`, `dynamicSessionStoresNoStaticDigestOrPlaintextCode`, `rejectsUnknownSessionType`, and controller JSON assertions that dynamic output exposes `sessionType=DYNAMIC_QR` and no token.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=CheckinSessionServiceTest,CheckinControllerTest" test`
  Expected: FAIL because the request and response have no session-type behavior.

- [ ] **Step 3: Implement session-type branching**

  Normalize only the two exact values `STATIC_CODE` and `DYNAMIC_QR`; default null to `STATIC_CODE`. Generate and persist a digest only for static sessions. Keep opening authorization, lock order, notifications, and audit unchanged.

- [ ] **Step 4: Run the focused tests and verify GREEN**

  Run: `.\mvnw.cmd "-Dtest=CheckinSessionServiceTest,CheckinControllerTest" test`
  Expected: PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
  git commit -m "feat: support dynamic check-in sessions"
  ```

### Task 3: Manager Dynamic Credential Endpoint

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinCodeService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinController.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/DynamicCheckinCodeServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java`

**Interfaces:**
- Consumes: `DynamicCheckinTokenStore.issue(long, Instant)`, `ActivityMapper`, `CheckinSessionMapper`, `OrganizationAuthorizationService`, and injected `Clock`.
- Produces: `DynamicCheckinCodeService.currentCode(Long actorId, Long sessionId)` returning `DynamicCodeView(Long sessionId, Long activityId, long windowEpochSecond, Instant expiresAt, String token)`; `GET /api/v1/check-in-sessions/{sessionId}/dynamic-code`.

- [ ] **Step 1: Write failing service and controller tests**

  Add tests for RBAC-before-Redis, dynamic/open/current session success, static-session rejection, closed/expired/final rejection, `503 DYNAMIC_CHECKIN_UNAVAILABLE` on Redis failure, token-redacted `toString()`, and exact response fields.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinCodeServiceTest,CheckinControllerTest" test`
  Expected: FAIL because the service and endpoint do not exist.

- [ ] **Step 3: Implement manager issuance**

  Perform MySQL reads and `checkin:manage` authorization before calling the store. Convert Redis access failures to `503 DYNAMIC_CHECKIN_UNAVAILABLE`; use existing not-found/conflict conventions for durable session state.

- [ ] **Step 4: Run the focused tests and verify GREEN**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinCodeServiceTest,CheckinControllerTest" test`
  Expected: PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
  git commit -m "feat: issue rotating dynamic check-in codes"
  ```

### Task 4: Dynamic Admission Before the MySQL Transaction

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/AttendanceQueryService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinController.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/DynamicCheckinServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/AttendanceQueryServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java`

**Interfaces:**
- Consumes: `DynamicCheckinTokenStore.verify(...)` and the proxied transactional `CheckinService`.
- Produces: `AttendanceQueryService.dynamicSelfRequest(Long userId, Long sessionId)` returning `DynamicCheckinTarget(Long activityId)` after membership authorization; `DynamicCheckinService.selfCheckin(Long userId, Long sessionId, DynamicCheckinRequest request)`; `DynamicCheckinRequest(long windowEpochSecond, String token)` with redacted `toString()`; `CheckinService.dynamicSelfCheckin(Long userId, Long sessionId, Long activityId, VerifiedCredential proof)`; `POST /api/v1/check-in-sessions/{sessionId}/dynamic-check-ins`.

- [ ] **Step 1: Write failing admission, transaction, and HTTP tests**

  Add tests `dynamicSelfRequestRequiresMembershipWithoutCarryingToken`, `redisFailureDoesNotCallTransactionalService`, `invalidCredentialDoesNotCallTransactionalService`, `validCredentialCallsTransactionalServiceWithProof`, `expiredProofAfterLockWaitCreatesNoRecord`, `proofForAnotherSessionIsRejected`, `duplicateDynamicCheckinMapsToAlreadyCheckedIn`, and request/exception string redaction tests.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinServiceTest,AttendanceQueryServiceTest,CheckinServiceTest,CheckinControllerTest" test`
  Expected: FAIL because the dynamic admission path does not exist.

- [ ] **Step 3: Implement admission and transactional reuse**

  Keep `DynamicCheckinService` non-transactional. Resolve the activity and require membership before Redis verification, but never place the token in the routing DTO. Map unavailable Redis to `503 DYNAMIC_CHECKIN_UNAVAILABLE`; map all malformed, missing, expired, wrong, future, and cross-session credentials to `422 INVALID_DYNAMIC_CHECKIN_CODE`. In `CheckinService`, reuse the existing row-lock, registration, unique-insert, attendance-update, and audit path; after acquiring locks, reject when `now >= proof.expiresAt()` before mutation.

- [ ] **Step 4: Run the focused tests and verify GREEN**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinServiceTest,AttendanceQueryServiceTest,CheckinServiceTest,CheckinControllerTest" test`
  Expected: PASS, including one-record behavior under duplicate-key conflict.

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
  git commit -m "feat: add dynamic self check-in flow"
  ```

### Task 5: Post-commit Dynamic Credential Cleanup

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinTokenInvalidator.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionService.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/DynamicCheckinTokenInvalidatorTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinSessionServiceTest.java`

**Interfaces:**
- Consumes: `DynamicCheckinTokenStore.evictRecent(long sessionId, Instant now)`.
- Produces: `DynamicCheckinTokenInvalidator.afterCommit(long sessionId, Instant now)`, registering Spring transaction synchronization when active and otherwise evicting immediately.

- [ ] **Step 1: Write failing commit-boundary tests**

  Add tests `closeEvictsDynamicKeysOnlyAfterCommit`, `rollbackDoesNotEvict`, `staticCloseDoesNotEvictDynamicKeys`, and `redisCleanupFailureDoesNotRollBackClosedSession`.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinTokenInvalidatorTest,CheckinSessionServiceTest" test`
  Expected: FAIL because no dynamic invalidator is wired.

- [ ] **Step 3: Implement best-effort post-commit cleanup**

  Schedule cleanup only after a successful dynamic-session close. Delete the current and previous window keys for the supplied close instant; log session ID and exception class only, never token data.

- [ ] **Step 4: Run the focused tests and verify GREEN**

  Run: `.\mvnw.cmd "-Dtest=DynamicCheckinTokenInvalidatorTest,CheckinSessionServiceTest" test`
  Expected: PASS.

- [ ] **Step 5: Commit**

  ```bash
  git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
  git commit -m "feat: clean dynamic codes after session close"
  ```

### Task 6: Frontend Credential Contract and Secret-safe Fragment Handling

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `frontend/src/api/checkins.ts`
- Modify: `frontend/src/api/checkins.test.ts`
- Create: `frontend/src/checkin/dynamicCheckinFragment.ts`
- Create: `frontend/src/checkin/dynamicCheckinFragment.test.ts`

**Interfaces:**
- Consumes: Stage 4B backend endpoints and browser `location`/`history` APIs.
- Produces: `DynamicCheckinCode`, `DynamicCheckinInput`, `getDynamicCheckinCode(sessionId)`, `submitDynamicCheckin(sessionId, input)`, `buildDynamicCheckinUrl(origin, activityId, credential)`, and `consumeDynamicCheckinFragment(location, history)`.

- [ ] **Step 1: Write failing API and fragment tests**

  Assert the management API opens with `sessionType: "DYNAMIC_QR"`, issuance and submission use the exact endpoints, the generated URL keeps `token` and `window` after `#` rather than `?`, malformed fragments return null, and successful consumption immediately calls `history.replaceState` with a URL that contains no token.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `npm test -- --run src/api/checkins.test.ts src/checkin/dynamicCheckinFragment.test.ts`
  Expected: FAIL because the dynamic API and fragment module do not exist.

- [ ] **Step 3: Add the QR dependency**

  Run: `npm install qrcode`
  Run: `npm install --save-dev @types/qrcode`
  Expected: `package.json` and `package-lock.json` record the runtime library and TypeScript declarations.

- [ ] **Step 4: Implement the frontend API and fragment contracts**

  Keep tokens out of error messages and do not normalize Base64URL credentials to uppercase. Build the QR target with `token` and `window` only in the fragment, and call `history.replaceState` before returning a successfully parsed credential.

- [ ] **Step 5: Run focused tests and typecheck and verify GREEN**

  Run: `npm test -- --run src/api/checkins.test.ts src/checkin/dynamicCheckinFragment.test.ts`
  Run: `npm run typecheck`
  Expected: PASS.

- [ ] **Step 6: Commit**

  ```bash
  git add frontend/package.json frontend/package-lock.json frontend/src/api/checkins.ts frontend/src/api/checkins.test.ts frontend/src/checkin
  git commit -m "feat: add dynamic check-in frontend contracts"
  ```

### Task 7: Manager QR Rotation and Member Confirmation UI

**Files:**
- Modify: `frontend/src/views/CheckinManagementView.vue`
- Modify: `frontend/src/views/CheckinManagementView.test.ts`
- Modify: `frontend/src/views/ActivityCheckinView.vue`
- Modify: `frontend/src/views/ActivityCheckinView.test.ts`
- Modify: `frontend/src/api/checkins.ts`

**Interfaces:**
- Consumes: Task 6 API/fragment functions and `qrcode.toDataURL(text)`.
- Produces: an automatically rotating manager QR/countdown and a member confirmation flow that submits the consumed fragment credential once.

- [ ] **Step 1: Write failing manager and member view tests**

  Add tests for explicit dynamic session opening, immediate credential request, refresh only when expiry approaches, QR URL containing no query-string token, Redis-unavailable state, fragment clearing before confirmation, canceled confirmation creating no request, successful dynamic submission, expired/invalid messaging, and existing manual check-in behavior.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run: `npm test -- --run src/views/CheckinManagementView.test.ts src/views/ActivityCheckinView.test.ts`
  Expected: FAIL because both views still implement static-only behavior.

- [ ] **Step 3: Implement minimal functional UI**

  Render the QR data URL and countdown in the existing management layout. Schedule the next fetch from `expiresAt` rather than fixed rapid polling, clear timers on unmount, and retain retry controls. On the member page, consume and erase the fragment on mount, show a confirmation action, then call the dynamic endpoint without placing the credential back in the URL or a global store.

- [ ] **Step 4: Run focused tests, full frontend tests, and build**

  Run: `npm test -- --run src/views/CheckinManagementView.test.ts src/views/ActivityCheckinView.test.ts`
  Run: `npm test -- --run`
  Run: `npm run build`
  Expected: all tests and production build pass.

- [ ] **Step 5: Commit**

  ```bash
  git add frontend/src/views frontend/src/api/checkins.ts
  git commit -m "feat: add rotating check-in qr experience"
  ```

### Task 8: Cross-layer Regression and Operational Documentation

**Files:**
- Modify: `README.md`
- Create: `docs/operations/stage-4-redis.md`
- Modify: `deploy/.env.example`
- Create: `backend/src/test/java/com/volunteerflow/Stage4DynamicCheckinContractTest.java`

**Interfaces:**
- Consumes: all Stage 4B backend configuration, endpoints, frontend route behavior, and deployment environment names.
- Produces: reproducible configuration/verification instructions and contract coverage preventing token leakage or accidental Redis readiness coupling.

- [ ] **Step 1: Write failing cross-layer contract tests**

  Assert documentation declares the three dynamic-code environment variables, Nginx receives no token in the QR query string, Redis stays excluded from readiness, dynamic failure behavior is documented as 503 plus manual-check-in fallback, and no source/logging contract serializes the raw token.

- [ ] **Step 2: Run the contract tests and verify RED**

  Run: `.\mvnw.cmd "-Dtest=Stage4DynamicCheckinContractTest" test`
  Expected: FAIL because Stage 4B operations documentation is absent.

- [ ] **Step 3: Update documentation and environment examples**

  Document 30-second rotation, 45-second absolute lifetime, VM Redis reuse, manual fallback, the two new endpoints, and the boundary that live phone/Redis verification remains unclaimed until run.

- [ ] **Step 4: Run all verification gates**

  Run: `.\mvnw.cmd test`
  Run: `npm test -- --run`
  Run: `npm run build`
  Expected: backend and frontend suites report zero failures and the frontend production build succeeds without a live Redis server.

- [ ] **Step 5: Commit**

  ```bash
  git add README.md docs/operations/stage-4-redis.md deploy/.env.example backend/src/test/java/com/volunteerflow/Stage4DynamicCheckinContractTest.java
  git commit -m "docs: add stage 4b dynamic check-in operations"
  ```
