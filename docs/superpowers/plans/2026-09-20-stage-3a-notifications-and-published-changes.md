# Stage 3A Notifications and Published Changes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add transactional in-app notifications and auditable published-activity/position changes without introducing Redis, Kafka, or Outbox.

**Architecture:** Keep the modular monolith. Business services call a narrow `NotificationWriter` inside their existing MySQL transaction; notification reads stay in the notification module. Published changes use a dedicated service that locks current rows, records before/after audit snapshots, then writes deduplicated notifications to affected users.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring Security 6, MyBatis-Plus, MySQL/Flyway, JUnit 5/Mockito/MockMvc, Vue 3, TypeScript, Axios, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-20-stage-3-notification-checkin-deployment-design.md`

## Global Constraints

- Use Java 17; do not introduce Java 21-only APIs.
- Do not add physical foreign keys or modify Flyway V1-V8.
- MySQL remains the only notification correctness source; do not use Redis, Kafka, application events, or Outbox.
- Activity changes, audit snapshots, and notifications commit or roll back together.
- Notify only `PENDING_REVIEW`, `CONFIRMED`, `WAITLISTED`, and users with a pending non-expired promotion offer.
- Do not expose another user's notifications or cross-organization management resources.
- Follow RED-GREEN-REFACTOR for every production behavior.

## Review Focus

- A duplicate notification command must be an idempotent success without hiding non-duplicate SQL failures; Task 2 tests duplicate and malformed batches separately.
- A cursor with the same timestamp as several rows must neither skip nor repeat notifications; Task 2 tests the `(create_time,id)` tie-breaker.
- A published-change request that loses a race with cancellation or finishing must fail after the row lock; Task 4 tests current locked state.
- An old terminal registration must not receive a later activity-change notification; Task 4 tests all active and terminal states.
- Increasing capacity while promotion offers already reserve seats must create only the number of offers that still fit; Task 5 tests reserved-capacity accounting.

---

## File Structure

New notification files:

```text
backend/src/main/java/com/volunteerflow/notification/
├─ Notification.java
├─ NotificationMapper.java
├─ NotificationWriter.java
├─ NotificationService.java
├─ NotificationController.java
└─ NotificationViews.java
```

New activity file:

```text
backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java
```

Frontend additions:

```text
frontend/src/api/notifications.ts
frontend/src/views/NotificationCenterView.vue
frontend/src/views/PublishedActivityManagementView.vue
frontend/src/stores/notifications.ts
```

## Task 1: Add the Stage 3 schema contract

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__add_stage3_notification_and_attendance_support.sql`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycle.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/FlywayMigrationContractTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/LombokEntityContractTest.java`

**Interfaces:**
- Produces: `RegistrationCycle#getAttendanceStatus()` and `setAttendanceStatus(String)` for Stage 3B.
- Produces: indexes named `idx_activity_status_end_id`, `idx_cycle_activity_status_id`, `idx_notification_user_created_id`, and `idx_checkin_session_activity_status_close_id`.

- [ ] **Step 1: Write the failing migration contract tests**

Add assertions that load V9 and require these literal schema effects:

```java
assertThat(v9).contains("attendance_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED'");
assertThat(v9).contains("KEY idx_activity_status_end_id (status, activity_end_at, id)");
assertThat(v9).contains("KEY idx_cycle_activity_status_id (activity_id, status, id)");
assertThat(v9).contains("KEY idx_notification_user_created_id (user_id, create_time, id)");
assertThat(v9).contains("KEY idx_checkin_session_activity_status_close_id");
```

Extend the Lombok entity contract to set and read `attendanceStatus` as `PENDING`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd '-Dtest=FlywayMigrationContractTest,LombokEntityContractTest' test
```

Expected: FAIL because V9 and `RegistrationCycle.attendanceStatus` do not exist.

- [ ] **Step 3: Add the minimal migration and entity field**

V9 must use `ALTER TABLE` statements and backfill with joined facts before making behavior depend on the column:

```sql
ALTER TABLE registration_cycle
    ADD COLUMN attendance_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED'
        COMMENT 'NOT_REQUIRED/PENDING/CHECKED_IN/NO_SHOW' AFTER status;

UPDATE registration_cycle c
LEFT JOIN checkin_record r ON r.registration_cycle_id = c.id AND r.status = 'VALID'
LEFT JOIN activity a ON a.id = c.activity_id
SET c.attendance_status = CASE
    WHEN c.status <> 'CONFIRMED' THEN 'NOT_REQUIRED'
    WHEN r.id IS NOT NULL THEN 'CHECKED_IN'
    WHEN a.activity_end_at <= CURRENT_TIMESTAMP(6) THEN 'NO_SHOW'
    ELSE 'PENDING'
END;

ALTER TABLE activity
    ADD KEY idx_activity_status_end_id (status, activity_end_at, id);
ALTER TABLE registration_cycle
    ADD KEY idx_cycle_activity_status_id (activity_id, status, id);
ALTER TABLE notification
    ADD KEY idx_notification_user_created_id (user_id, create_time, id);
ALTER TABLE checkin_session
    ADD KEY idx_checkin_session_activity_status_close_id
        (activity_id, status, closes_at, id);
```

Add `private String attendanceStatus;` to `RegistrationCycle`.

- [ ] **Step 4: Run focused tests and the migration checksum contract**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V9__add_stage3_notification_and_attendance_support.sql backend/src/main/java/com/volunteerflow/registration/RegistrationCycle.java backend/src/test/java/com/volunteerflow/infrastructure/persistence
git commit -m "feat: add stage 3 persistence support"
```

## Task 2: Implement notification write and read boundaries

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/notification/Notification.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationWriter.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationService.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationController.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationViews.java`
- Create: `backend/src/test/java/com/volunteerflow/notification/NotificationServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/notification/NotificationControllerTest.java`

**Interfaces:**
- Produces: `NotificationWriter.write(NotificationCommand)`.
- Produces: `NotificationWriter.writeMany(Collection<Long>, NotificationTemplate)` returning the number of unique recipients attempted.
- Produces: `NotificationService.page(Long userId, String cursor, int size)`, `unreadCount`, `markRead`, and `markAllRead`.

- [ ] **Step 1: Write failing service tests for writes, deduplication, cursor ties, and ownership**

Define the public command records in the test's expected API:

```java
new NotificationWriter.NotificationCommand(
    7L, 42L, "PROMOTION_OFFER", "递补邀请", "请及时确认",
    "PROMOTION_OFFER", 99L, "promotion-offer:99:created");
```

Test that `writeMany` deduplicates repeated user IDs before constructing rows, uses one stable business key per user, and propagates non-duplicate mapper failures. Test pages containing equal `createTime` values use `id` as the second cursor component. Test user 42 receives `NOTIFICATION_NOT_FOUND` for user 43's notification.

- [ ] **Step 2: Run tests and verify RED**

```powershell
.\mvnw.cmd '-Dtest=NotificationServiceTest,NotificationControllerTest' test
```

Expected: test compilation fails because the notification classes do not exist.

- [ ] **Step 3: Implement entities, batch SQL, cursor codec, service, and controller**

Use this interface exactly:

```java
public interface NotificationWriter {
  void write(NotificationCommand command);
  int writeMany(Collection<Long> userIds, NotificationTemplate template);

  record NotificationCommand(
      Long organizationId, Long userId, String type, String title, String content,
      String businessType, Long businessId, String deduplicationKey) {}

  record NotificationTemplate(
      Long organizationId, String type, String title, String content,
      String businessType, Long businessId, String deduplicationKey) {}
}
```

`NotificationMapper` must use `(create_time < cursorTime OR (create_time = cursorTime AND id < cursorId))`, ordered by `create_time DESC,id DESC`, with `LIMIT size+1`. Batch insert uses `ON DUPLICATE KEY UPDATE id=id`, never `INSERT IGNORE`.

Controller routes:

```java
GET  /api/v1/users/me/notifications
GET  /api/v1/users/me/notifications/unread-count
POST /api/v1/notifications/{id}/read
POST /api/v1/users/me/notifications/read-all
```

Clamp size to 1-50 and reject malformed cursors with `INVALID_NOTIFICATION_CURSOR`.

- [ ] **Step 4: Run focused tests and full backend tests**

```powershell
.\mvnw.cmd '-Dtest=NotificationServiceTest,NotificationControllerTest' test
.\mvnw.cmd test
```

Expected: all tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/notification backend/src/test/java/com/volunteerflow/notification
git commit -m "feat: add transactional notification center API"
```

## Task 3: Emit notifications from existing registration workflows

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationSubmissionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationReviewService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java`
- Modify tests with matching class names under `backend/src/test/java/com/volunteerflow/registration/`
- Modify: `backend/src/test/java/com/volunteerflow/activity/ActivityServiceTest.java`

**Interfaces:**
- Consumes: `NotificationWriter` from Task 2.
- Produces: transactional notifications for submission result, review result, offer creation/expiry, and activity cancellation.

- [ ] **Step 1: Add one failing behavior test per trigger**

Each test must assert the persisted business outcome and captured `NotificationCommand`, not merely that a mock was called. Use an in-memory capturing writer that enforces duplicate keys. Representative expectation:

```java
assertThat(writer.commands())
    .extracting(NotificationCommand::deduplicationKey)
    .containsExactly("registration-review:" + cycleId + ":CONFIRMED");
assertThat(cycle.getStatus()).isEqualTo("CONFIRMED");
```

Also make the writer throw once and assert the service propagates the exception so Spring can roll back the enclosing transaction.

- [ ] **Step 2: Run the five focused test classes and verify RED**

Expected: FAIL because constructors do not accept `NotificationWriter` and no commands are emitted.

- [ ] **Step 3: Inject the writer and emit commands after successful state writes**

Use stable keys from the spec. Activity cancellation must query affected user IDs before terminal state changes make them ineligible, deduplicate IDs, and call `writeMany` in the same transaction. Do not notify users for their own cancel/accept/decline commands.

- [ ] **Step 4: Run focused and full backend suites**

```powershell
.\mvnw.cmd '-Dtest=RegistrationSubmissionServiceTest,RegistrationReviewServiceTest,PromotionServiceTest,PromotionExpiryJobTest,ActivityServiceTest' test
.\mvnw.cmd test
```

Expected: PASS with no existing Stage 2 regression.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/activity/ActivityService.java backend/src/test/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/activity/ActivityServiceTest.java
git commit -m "feat: notify registration lifecycle changes"
```

## Task 4: Add auditable published activity changes

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityController.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/audit/AuditService.java`
- Create: `backend/src/test/java/com/volunteerflow/activity/ActivityChangeServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/activity/ActivityControllerTest.java`

**Interfaces:**
- Consumes: `NotificationWriter.writeMany`.
- Produces: `ActivityChangeService.changeActivity(Long actorId, Long activityId, ActivityChangeRequest)` returning `ActivityChangeResult(Activity activity, Long auditId, int notifiedRecipients)`.
- Produces: `AuditService.recordChange(Long, Long, String, String, Long, Map<String,Object>, Map<String,Object>, String)` returning the audit ID used in notification deduplication.

- [ ] **Step 1: Write failing tests for allowed fields, time rules, locked state, no-op, snapshots, and recipients**

Use a fixed `Clock`. Include a race test whose initial snapshot is `PUBLISHED` but whose locked row is `CANCELED`; expect `ACTIVITY_CHANGE_NOT_ALLOWED`. Parameterize recipient statuses so only active statuses and a pending non-expired offer receive the notification.

Expected request shape:

```java
new ActivityChangeRequest(
    "新标题", "新介绍", "新地点", newRegistrationEnd,
    newFreeCancelDeadline, newStart, newEnd, "场地调整");
```

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\mvnw.cmd '-Dtest=ActivityChangeServiceTest,ActivityControllerTest' test
```

Expected: FAIL because the change service and endpoint do not exist.

- [ ] **Step 3: Implement current-row locking, field diffs, audit snapshots, and notifications**

`AuditService.recordChange` must serialize explicit before/after maps rather than whole entities:

```java
public Long recordChange(
    Long organizationId, Long actorId, String action, String resourceType,
    Long resourceId, Map<String, Object> before, Map<String, Object> after, String reason)
```

`RegistrationCycleMapper.selectAffectedUserIds(activityId, now)` returns distinct IDs for active cycles plus pending offers. The change service validates after acquiring `activity FOR UPDATE`, writes only changed fields, records the audit, then writes notifications using `activity-change:{activityId}:{auditId}`.

- [ ] **Step 4: Add the controller route and MockMvc contract**

Add `POST /api/v1/activities/{id}/changes`. Assert 200 response, 403 permission denial, 409 final activity, and 422 invalid/no-op request.

- [ ] **Step 5: Run focused and full backend suites**

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/activity backend/src/main/java/com/volunteerflow/audit backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java backend/src/test/java/com/volunteerflow/activity
git commit -m "feat: add published activity change workflow"
```

## Task 5: Add published position changes, draft additions, and publication

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityController.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityQuestionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityPositionMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Modify: `backend/src/test/java/com/volunteerflow/activity/ActivityChangeServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/activity/ActivityQuestionServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/PromotionServiceTest.java`

**Interfaces:**
- Produces: `changePosition(Long, Long, PositionChangeRequest)` returning `PositionChangeResult(ActivityPosition, Long, int)`.
- Produces: `publishPosition(Long, Long)` returning `PositionPublicationResult(ActivityPosition, Long, int)` and published-activity `addPosition` behavior.
- Produces: `PromotionService.fillAvailableFirstComeSeats(ActivityPosition lockedPosition, Long actorId, String reason)`.

- [ ] **Step 1: Write failing tests for immutable mode, capacity decrease, service times, draft questions, publication, and reserved seats**

Test that a published activity creates new positions as `DRAFT`; only that draft position's questions remain editable; publication changes it to `ACTIVE`. Test capacity 20 with 18 confirmed and one pending offer creates exactly one additional offer when capacity becomes 20, not two.

- [ ] **Step 2: Run focused tests and verify RED**

Expected: FAIL on missing methods and current `ACTIVITY_NOT_DRAFT` behavior.

- [ ] **Step 3: Implement position change and publication under lock order activity → position → questions**

Add routes:

```text
POST /api/v1/positions/{positionId}/changes
POST /api/v1/positions/{positionId}/publication
```

Keep the existing `POST /activities/{activityId}/positions`; draft activities create `ACTIVE` positions as before, published activities require `activity:change` and create `DRAFT` positions. Publishing validates combined question count, writes audit and notifies affected users.

- [ ] **Step 4: Implement capacity-fill logic without direct confirmation**

Count `CONFIRMED` plus pending non-expired offers while holding the position lock. Repeatedly create offers only while occupied seats remain below capacity and eligible first-come candidates exist. Stop cleanly when the queue is empty.

- [ ] **Step 5: Run focused and full backend suites**

Expected: PASS, including publication/question concurrency tests from Stage 2.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/activity backend/src/main/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/activity backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: add published position change workflow"
```

## Task 6: Build the notification frontend

**Files:**
- Create: `frontend/src/api/notifications.ts`
- Create: `frontend/src/stores/notifications.ts`
- Create: `frontend/src/views/NotificationCenterView.vue`
- Create: `frontend/src/views/NotificationCenterView.test.ts`
- Modify: `frontend/src/components/AppShell.vue`
- Create: `frontend/src/components/AppShell.test.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes API routes from Task 2.
- Produces: `useNotificationStore()` with `unreadCount`, `refreshUnreadCount`, `startPolling`, and `stopPolling`.

- [ ] **Step 1: Write failing Vitest tests for cursor loading, read navigation, read-all, badge, and visibility polling**

Use fake timers. Assert no polling while `document.hidden`, an immediate refresh on `visibilitychange` to visible, and timer cleanup after logout/unmount. Assert a notification click waits for mark-read before router navigation.

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
cd frontend
npm test -- --run src/views/NotificationCenterView.test.ts src/components/AppShell.test.ts
```

Expected: FAIL because components/store/routes do not exist.

- [ ] **Step 3: Implement typed API, Pinia store, view, badge, and route**

Use `GET /users/me/notifications`, `GET /users/me/notifications/unread-count`, and the two POST commands. Route `/notifications` must be authenticated. Map business types to existing activity/registration routes; unknown types remain readable without navigation.

- [ ] **Step 4: Run focused tests, all frontend tests, typecheck, and build**

```powershell
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add notification center experience"
```

## Task 7: Build the published-change management frontend

**Files:**
- Modify: `frontend/src/api/activities.ts`
- Create: `frontend/src/views/PublishedActivityManagementView.vue`
- Create: `frontend/src/views/PublishedActivityManagementView.test.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes Stage 3A activity/position change and publication APIs.
- Produces route `/activities/:id/manage`.

- [ ] **Step 1: Write failing tests for field diffs, mandatory reason, no-op rejection, capacity warning, and stale request isolation**

Use literal before/after fixtures. Assert the confirmation summary names only changed fields, the request includes trimmed reason, capacity decrease is blocked before request, and an old activity response cannot replace the newly navigated activity.

- [ ] **Step 2: Run focused test and verify RED**

```powershell
npm test -- --run src/views/PublishedActivityManagementView.test.ts
```

Expected: FAIL because the view and API functions do not exist.

- [ ] **Step 3: Implement activity/position forms, draft-position flow, and publication action**

Render only allowed fields. Keep change form state local to the page. Display notification recipient count returned by the backend. Treat 403, 409, and 422 separately.

- [ ] **Step 4: Run frontend regression**

```powershell
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add published activity management UI"
```

## Task 8: Stage 3A regression gate

**Files:**
- Modify only files required by failures discovered in this task.

**Interfaces:**
- Produces: a green Stage 3A baseline for Stage 3B.

- [ ] **Step 1: Run backend clean verification**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: all tests pass and the Java 17 JAR is built.

- [ ] **Step 2: Run frontend verification**

```powershell
cd ..\frontend
npm test -- --run
npm run typecheck
npm run build
```

Expected: all commands pass.

- [ ] **Step 3: Inspect the diff and repository state**

```powershell
cd ..
git diff --check
git status --short
```

Expected: no whitespace errors and only intentional changes.

Any correction discovered by this gate must return to the owning task: add a focused failing test, verify RED, make the minimal fix, rerun that task's tests, and commit the exact corrected paths before repeating this gate. Do not create an empty gate commit.
