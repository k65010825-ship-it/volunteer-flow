# Stage 3B Check-in and Attendance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add expiring ordinary check-in codes, self check-in, audited manual check-in, indexed activity finalization, and attendance statistics.

**Architecture:** MySQL owns session validity, uniqueness, attendance state, and finalization. Check-in services lock the activity/session/registration rows in a stable order, store only a SHA-256 code digest, and update the check-in record, attendance state, audit, and notification facts in one transaction. A bounded scheduler scans due activities by the Stage 3 index and finalizes each in an independent transaction.

**Tech Stack:** Java 17, Spring Boot scheduling and transactions, MyBatis-Plus/custom SQL, MySQL/Flyway, JUnit 5/Mockito/MockMvc, Vue 3, TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-20-stage-3-notification-checkin-deployment-design.md`

## Global Constraints

- Complete Stage 3A before executing this plan.
- Use the V9 `attendance_status` column and indexes created by Stage 3A; do not edit V9 after it has been applied to a shared database.
- Default session duration is 15 minutes; accepted range is 5-120 minutes.
- Store only the SHA-256 digest; never log, audit, persist, or re-return the plaintext code.
- A member may have at most one check-in record per activity.
- Self check-in requires an effective `CONFIRMED` cycle and an open, unexpired session.
- Manual check-in requires `checkin:manage`, a 1-500 character reason, and a previously confirmed registration.
- Finalization must use indexed keyset batches, no table-wide Java load and no `OFFSET`.
- Follow RED-GREEN-REFACTOR for every production behavior.

## Review Focus

- Opening two sessions concurrently for one activity must leave exactly one effective OPEN session; Task 2 tests the activity-row lock.
- A code at exactly `closesAt` must be rejected, while one millisecond before remains valid; Task 3 pins the boundary.
- Self check-in racing manual check-in for the same user must create one record and one attendance transition; Task 3 tests the unique-key conflict mapping.
- A finalizer transaction that fails after claiming an activity must roll the status back so a later run can retry; Task 5 tests rollback/retry.
- A large due-activity set must remain bounded by batch size and max batches and must use the composite index; Tasks 5 and 7 test both behavior and `EXPLAIN`.

---

## File Structure

```text
backend/src/main/java/com/volunteerflow/checkin/
├─ CheckinSession.java
├─ CheckinSessionMapper.java
├─ CheckinRecord.java
├─ CheckinRecordMapper.java
├─ CheckinCodeGenerator.java
├─ SecureCheckinCodeGenerator.java
├─ CheckinSessionService.java
├─ CheckinService.java
├─ AttendanceQueryService.java
├─ ActivityFinalizationProperties.java
├─ ActivityFinalizationService.java
├─ ActivityFinalizationJob.java
├─ CheckinController.java
└─ CheckinViews.java
```

Frontend additions:

```text
frontend/src/api/checkins.ts
frontend/src/views/ActivityCheckinView.vue
frontend/src/views/CheckinManagementView.vue
```

## Task 1: Maintain attendance state across registration transitions

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationSubmissionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationReviewService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCancellationService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationViews.java`
- Modify matching tests under `backend/src/test/java/com/volunteerflow/registration/`

**Interfaces:**
- Produces: every `RegistrationCycle` has one of `NOT_REQUIRED`, `PENDING`, `CHECKED_IN`, or `NO_SHOW`.
- Produces: member and management projections expose `attendanceStatus`.

- [ ] **Step 1: Write failing transition tests**

Add literal assertions for this table:

```text
FIRST_COME free seat -> CONFIRMED / PENDING
FIRST_COME full      -> WAITLISTED / NOT_REQUIRED
REVIEW submit        -> PENDING_REVIEW / NOT_REQUIRED
review confirm       -> CONFIRMED / PENDING
review waitlist      -> WAITLISTED / NOT_REQUIRED
promotion accept     -> CONFIRMED / PENDING
cancel/decline/expire-> terminal / NOT_REQUIRED
```

Assert query projections return the stored value rather than deriving it from status.

- [ ] **Step 2: Run registration tests and verify RED**

```powershell
cd backend
.\mvnw.cmd '-Dtest=RegistrationSubmissionServiceTest,RegistrationReviewServiceTest,PromotionServiceTest,PromotionExpiryJobTest,RegistrationCancellationServiceTest,RegistrationQueryServiceTest' test
```

Expected: FAIL because transitions and projections do not maintain attendance.

- [ ] **Step 3: Add minimal state assignments and projection fields**

Set attendance in the same methods that set registration status. Do not add a second transaction or derive attendance in controllers.

- [ ] **Step 4: Run focused and full backend tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: track registration attendance state"
```

## Task 2: Implement secure check-in sessions

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinSession.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinCodeGenerator.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/SecureCheckinCodeGenerator.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionService.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/SecureCheckinCodeGeneratorTest.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/CheckinSessionServiceTest.java`

**Interfaces:**
- Produces: `CheckinSessionService.open(Long actorId, Long activityId, OpenSessionRequest)` returning `OpenedSession(Long sessionId, Long activityId, LocalDateTime opensAt, LocalDateTime closesAt, String code)` with plaintext code exactly once.
- Produces: `close`, `currentForMember`, and `currentForManager`.

- [ ] **Step 1: Write failing generator tests**

Test 1,000 generated codes against `^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$`, assert deterministic digest of literal `ABCD2345`, and assert normalization trims and uppercases input. Test the digest API returns bytes suitable for `MessageDigest.isEqual`.

- [ ] **Step 2: Run generator test and verify RED**

Expected: compilation FAIL because the generator does not exist.

- [ ] **Step 3: Implement the generator using one injected `SecureRandom`**

Interface:

```java
public interface CheckinCodeGenerator {
  String generate();
  byte[] digest(String normalizedCode);
  String normalize(String submittedCode);
}
```

Persist digest as lowercase hex only at the repository boundary. Production code must never log the generated value.

- [ ] **Step 4: Write failing session-service tests**

Test permission, 5/120-minute boundaries, default 15 minutes, final activity rejection, expired-session cleanup, concurrent open serialization, close idempotency, and plaintext returned only by `open`.

- [ ] **Step 5: Run session tests and verify RED**

Expected: FAIL because session entity/mapper/service are missing.

- [ ] **Step 6: Implement session entity, locking queries, and service**

Required query semantics:

```sql
SELECT * FROM checkin_session
WHERE activity_id=#{activityId} AND status='OPEN' AND closes_at > #{now}
ORDER BY opens_at DESC,id DESC LIMIT 1 FOR UPDATE;
```

Lock order is activity then session. Opening marks expired OPEN rows as `EXPIRED`, inserts the new row, audits `checkin.session.opened`, and writes the confirmed-member notification through `NotificationWriter`.

- [ ] **Step 7: Run focused and full backend tests**

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
git commit -m "feat: add secure check-in sessions"
```

## Task 3: Implement self check-in and manual check-in

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinRecord.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinRecordMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/CheckinServiceTest.java`

**Interfaces:**
- Produces: `selfCheckin(Long userId, Long sessionId, CheckinRequest)` returning `CheckinResult(Long recordId, Long activityId, Long userId, String method, LocalDateTime checkedInAt)`.
- Produces: `manualCheckin(Long actorId, Long sessionId, ManualCheckinRequest)` returning the same `CheckinResult` projection.

- [ ] **Step 1: Write failing self-check-in tests**

Cover correct code, wrong code, exact expiry boundary, inactive membership, non-confirmed cycle, cross-activity session, already checked in, and activity already finished. Assert successful behavior inserts one `SELF_SERVICE` record, changes attendance to `CHECKED_IN`, and writes audit without plaintext code.

- [ ] **Step 2: Run the focused test and verify RED**

Expected: FAIL because record/service methods are missing.

- [ ] **Step 3: Implement self check-in with stable lock order**

Use activity/session, membership, confirmed cycle, then existing record checks. Compare digest bytes with `MessageDigest.isEqual`. Map the database unique-key conflict to `ALREADY_CHECKED_IN`.

- [ ] **Step 4: Write failing manual and race tests**

Cover missing/blank/501-character reason, missing permission, unrelated registration, a registration that was never confirmed, valid `NO_SHOW` correction, and self/manual race. For the race fixture, assert one record and final attendance `CHECKED_IN` regardless of winner.

- [ ] **Step 5: Implement manual check-in**

Manual check-in stores method `MANUAL`, `operatorId`, trimmed reason, and audit action `checkin.manual.created`. It may run after session/activity close but still requires a session belonging to the activity and a historically confirmed cycle.

- [ ] **Step 6: Run focused and full backend tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/checkin backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java backend/src/test/java/com/volunteerflow/checkin
git commit -m "feat: add self and manual check-in"
```

## Task 4: Expose check-in and attendance APIs

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinViews.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/AttendanceQueryService.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/CheckinController.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/AttendanceQueryServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java`

**Interfaces:**
- Consumes: session and check-in services from Tasks 2-3.
- Produces the six API routes from design sections 7-8.

- [ ] **Step 1: Write failing query tests for current session, current user status, and position summaries**

Hand-derive totals for a fixture with two positions, checked-in members, no-shows, pending attendance, and canceled cycles. Assert canceled and superseded cycles are excluded and zero-denominator rate is `0`.

- [ ] **Step 2: Write failing MockMvc tests for all routes and error mappings**

Expected route contracts:

```text
POST /api/v1/activities/{activityId}/check-in-sessions
POST /api/v1/check-in-sessions/{sessionId}/closure
GET  /api/v1/activities/{activityId}/check-in-sessions/current
POST /api/v1/check-in-sessions/{sessionId}/check-ins
POST /api/v1/check-in-sessions/{sessionId}/manual-check-ins
GET  /api/v1/activities/{activityId}/attendance-summary
```

Assert 401, 403, 404, 409, and 422 behavior separately.

- [ ] **Step 3: Run focused tests and verify RED**

Expected: FAIL because query/controller/view types do not exist.

- [ ] **Step 4: Implement projections, aggregate SQL, and controller validation**

Use SQL group aggregation by position rather than loading every cycle into Java. Return IDs as strings through the existing global Jackson configuration. Never return `staticCodeHash`.

- [ ] **Step 5: Run focused and full backend tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/checkin
git commit -m "feat: expose check-in and attendance APIs"
```

## Task 5: Add indexed, bounded activity finalization

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/checkin/ActivityFinalizationProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/ActivityFinalizationService.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/ActivityFinalizationJob.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/ActivityFinalizationJobTest.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/ActivityFinalizationServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/checkin/ActivityFinalizationConfigurationTest.java`

**Interfaces:**
- Produces: configurable `scanDelay`, `batchSize=100`, and `maxBatchesPerRun`.
- Produces: `finalizeOne(Long activityId, LocalDateTime now)` in `REQUIRES_NEW`.

- [ ] **Step 1: Write failing job tests for keyset cursors and bounds**

Use mapper pages with repeated end timestamps. Assert the next call receives the last `(endAt,id)`, never an offset; processing stops at an empty page or `maxBatchesPerRun`; one exception is logged and later IDs are still attempted.

- [ ] **Step 2: Write failing service tests for claim, rollback, attendance, and retry**

Assert affected-row count zero performs no work. Assert success changes only `CONFIRMED` cycles: rows with records become `CHECKED_IN`, rows without become `NO_SHOW`. Make audit insertion fail and assert the transaction exception leaves the activity eligible for a later retry.

- [ ] **Step 3: Run focused tests and verify RED**

Expected: FAIL because finalization components and mapper methods do not exist.

- [ ] **Step 4: Implement indexed mapper methods, service, job, and validated properties**

Mapper page signature:

```java
List<DueActivityId> selectDueIds(
    LocalDateTime now, LocalDateTime cursorEndAt, Long cursorId, int batchSize);
```

The mapper SQL must read `FROM activity FORCE INDEX (idx_activity_status_end_id)`, filter `status='PUBLISHED' AND activity_end_at<=#{now}`, apply the `(activity_end_at,id)` cursor, order by those same columns, and use `LIMIT #{batchSize}`. It must never use `OFFSET`.

Claim SQL:

```sql
UPDATE activity SET status='FINISHED'
WHERE id=#{id} AND status='PUBLISHED' AND activity_end_at <= #{now};
```

Bind configuration below `volunteerflow.checkin.finalization`. Reject batch size outside 1-500 and max batches outside 1-100. Register `ActivityFinalizationProperties` alongside `PromotionExpiryProperties` in `VolunteerFlowApplication`.

- [ ] **Step 5: Run focused and full backend tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/checkin backend/src/main/java/com/volunteerflow/activity/ActivityMapper.java backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java backend/src/main/resources/application.yml backend/src/test/java/com/volunteerflow/checkin
git commit -m "feat: finalize activity attendance in bounded batches"
```

## Task 6: Build member and administrator check-in views

**Files:**
- Create: `frontend/src/api/checkins.ts`
- Create: `frontend/src/views/ActivityCheckinView.vue`
- Create: `frontend/src/views/ActivityCheckinView.test.ts`
- Create: `frontend/src/views/CheckinManagementView.vue`
- Create: `frontend/src/views/CheckinManagementView.test.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes APIs from Task 4.
- Produces routes `/activities/:id/check-in` and `/activities/:id/check-in-management`.

- [ ] **Step 1: Write failing member-view tests**

Cover countdown from server `closesAt`, code normalization, submit disabling, successful reload state, and distinct messages for invalid code, closed session, ineligible user, and duplicate check-in. Use fake timers and a fixed server timestamp.

- [ ] **Step 2: Write failing management-view tests**

Cover 5/120 bounds, default 15, one-time code warning, copy action, early closure, member search, mandatory manual reason, `NO_SHOW` correction, aggregate totals, and stale response isolation after route changes.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
cd frontend
npm test -- --run src/views/ActivityCheckinView.test.ts src/views/CheckinManagementView.test.ts
```

Expected: FAIL because API and views do not exist.

- [ ] **Step 4: Implement typed API and both views**

Never place the plaintext code in Pinia, localStorage, sessionStorage, URL query, or console. Keep it in component memory only and clear it when closing/navigating.

- [ ] **Step 5: Run all frontend verification**

```powershell
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src
git commit -m "feat: add member and admin check-in views"
```

## Task 7: Add isolated real-MySQL Stage 3 acceptance

**Files:**
- Create: `backend/src/test/java/com/volunteerflow/Stage3VmAcceptanceIT.java`
- Modify: `README.md`

**Interfaces:**
- Consumes all Stage 3A and Stage 3B service APIs.
- Produces an explicit, non-default live-database acceptance test with precise cleanup.

- [ ] **Step 1: Write the acceptance test before running it**

Use a unique `s3it-<uuid>` prefix and record every generated organization/user ID. Test notification triggers and recipient exclusions, activity change, session digest storage, member check-in, duplicate race, manual check-in, finalization, `NO_SHOW` correction, and attendance summaries.

Run `EXPLAIN` for the due-activity query and assert `key` equals `idx_activity_status_end_id` or is one of MySQL's explicit equivalent chosen keys while `type` is not `ALL`.

Cleanup must delete exact child rows before parent rows and assert zero remaining generated organizations/users. Do not call `TRUNCATE`, delete without IDs/prefixes, or execute the global scheduler against unrelated rows.

- [ ] **Step 2: Run the live test and verify its first failure**

```powershell
$env:SPRING_CONFIG_ADDITIONAL_LOCATION='file:D:/javaweb/VolunteerFlow/backend/config/application-local.yml'
.\mvnw.cmd '-Dtest=Stage3VmAcceptanceIT' test
```

Expected: PASS because every production behavior was already driven by a focused failing test in Tasks 1-6. This cross-module release gate introduces no new production behavior. Infrastructure connection failures are diagnosed separately from business failures.

- [ ] **Step 3: Fix only acceptance gaps through a failing focused test first**

For every discovered product defect, add a focused unit/integration test that reproduces it, verify RED, make the minimal implementation correction, then rerun that focused test before returning to the live acceptance test.

- [ ] **Step 4: Rerun live acceptance and full verification**

```powershell
.\mvnw.cmd '-Dtest=Stage3VmAcceptanceIT' test
.\mvnw.cmd clean verify
cd ..\frontend
npm test -- --run
npm run typecheck
npm run build
```

Expected: all commands PASS and cleanup assertions succeed.

- [ ] **Step 5: Document only measured results and commit**

Update README with actual test counts and observed outcomes. Do not claim real-user usage, throughput, latency, Redis dynamic codes, or Kafka delivery.

```powershell
git add backend/src/test/java/com/volunteerflow/Stage3VmAcceptanceIT.java README.md
git commit -m "test: verify stage 3 against live mysql"
```
