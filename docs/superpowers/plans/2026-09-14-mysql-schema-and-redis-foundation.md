# MySQL Schema and Redis Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the approved VolunteerFlow V2-V8 MySQL schema, apply it safely to the configured development database, and add an optional Redis foundation that no core business path depends on.

**Architecture:** Flyway migrations remain the only schema writer and are split by domain. All relationships are logical references with indexes and application-layer ownership checks; database unique keys protect concurrency-sensitive uniqueness. Spring Boot auto-configures Lettuce and `StringRedisTemplate`, while a focused key factory owns the future key namespace.

**Tech Stack:** Java 17, Spring Boot 3.5.16, MyBatis-Plus 3.5.17, Flyway, MySQL 8, Spring Data Redis, Lettuce, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-14-mysql-schema-and-redis-foundation-design.md`

## Global Constraints

- Do not create physical foreign keys, `REFERENCES`, or cascade operations.
- Do not modify `V1__create_schema_metadata.sql`.
- Do not commit database or Redis credentials.
- Preserve the user's current uncommitted database connection until safe migration verification is complete.
- MySQL remains the source of truth and application startup must not execute a Redis command.
- Redis work in this plan is foundation-only: no cache annotations, rate limiting, check-in codes, or distributed locks.
- Use MyBatis-Plus `ASSIGN_ID` compatible signed `BIGINT` primary keys.

---

### Task 1: Add migration contract tests

**Files:**
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/FlywayMigrationContractTest.java`
- Test: `backend/src/main/resources/db/migration/V2__create_account_tables.sql`
- Test: `backend/src/main/resources/db/migration/V3__create_organization_tables.sql`
- Test: `backend/src/main/resources/db/migration/V4__create_rbac_tables.sql`
- Test: `backend/src/main/resources/db/migration/V5__create_activity_tables.sql`
- Test: `backend/src/main/resources/db/migration/V6__create_registration_tables.sql`
- Test: `backend/src/main/resources/db/migration/V7__create_collaboration_tables.sql`
- Test: `backend/src/main/resources/db/migration/V8__create_outbox_table.sql`

**Interfaces:**
- Consumes: classpath migration directory `db/migration`.
- Produces: a regression contract proving migration presence, table coverage, logical-only references, and required uniqueness declarations.

- [ ] **Step 1: Write the failing contract test**

Create a JUnit test with the exact expected table set:

```java
private static final Set<String> EXPECTED_TABLES = Set.of(
        "app_user", "refresh_session", "organization", "organization_invite",
        "organization_member", "rbac_permission", "rbac_role", "rbac_role_permission",
        "activity", "activity_position", "activity_change", "activity_question",
        "activity_position_question", "registration", "registration_cycle",
        "registration_answer", "promotion_offer", "checkin_session", "checkin_record",
        "notification", "audit_log", "outbox_event");
```

The test reads V2 through V8 as UTF-8, extracts `CREATE TABLE` names, asserts equality with this set, rejects case-insensitive `FOREIGN KEY`, `REFERENCES`, and cascade clauses, and asserts the following fragments exist:

```text
UNIQUE KEY uk_app_user_username
UNIQUE KEY uk_app_user_student_number
UNIQUE KEY uk_organization_member_org_user
UNIQUE KEY uk_rbac_role_org_name
UNIQUE KEY uk_rbac_role_permission_role_permission
UNIQUE KEY uk_registration_activity_user
UNIQUE KEY uk_registration_cycle_number
UNIQUE KEY uk_registration_cycle_waitlist_sequence
UNIQUE KEY uk_checkin_record_activity_user
UNIQUE KEY uk_outbox_event_event_id
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml -Dtest=FlywayMigrationContractTest test -ntp
```

Expected: FAIL because V2 through V8 do not exist.

- [ ] **Step 3: Commit only after Tasks 2-4 make this test green**

This test and the migration files form one independently reviewable database deliverable.

---

### Task 2: Create account, organization, and RBAC migrations

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__create_account_tables.sql`
- Create: `backend/src/main/resources/db/migration/V3__create_organization_tables.sql`
- Create: `backend/src/main/resources/db/migration/V4__create_rbac_tables.sql`

**Interfaces:**
- Consumes: table and index definitions in spec sections 3.1 through 3.3.
- Produces: account, refresh-session, organization, invitation, membership, role, permission, and role-permission tables.

- [ ] **Step 1: Implement V2**

Create `app_user` and `refresh_session` with `BIGINT` assigned IDs, `DATETIME(6)` audit timestamps, named unique keys for username, student number, and token hash, plus the specified lookup indexes. Do not insert administrator credentials.

- [ ] **Step 2: Implement V3**

Create `organization`, `organization_invite`, and `organization_member`. Name the membership business key `uk_organization_member_org_user`; add indexes for every organization, user, role, creator, status, and expiry lookup described by the spec.

- [ ] **Step 3: Implement V4 and permission seed data**

Create `rbac_permission`, `rbac_role`, and `rbac_role_permission`. Insert exactly the 21 permission codes listed in spec section 3.3 using deterministic assigned IDs from `1001` through `1021`, so the migration is repeatable and contains no generated values.

- [ ] **Step 4: Run the focused contract test**

Expected: still RED because V5 through V8 are intentionally absent.

---

### Task 3: Create activity and registration migrations

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__create_activity_tables.sql`
- Create: `backend/src/main/resources/db/migration/V6__create_registration_tables.sql`

**Interfaces:**
- Consumes: spec sections 3.4 and 3.5.
- Produces: activity/position/question/change and registration/cycle/answer/promotion storage.

- [ ] **Step 1: Implement V5**

Create `activity`, `activity_position`, `activity_question`, `activity_position_question`, and `activity_change`. Preserve `next_waitlist_sequence BIGINT NOT NULL DEFAULT 1`; use JSON for question options and change snapshots; create the approved named unique keys and lookup indexes.

- [ ] **Step 2: Implement V6**

Create `registration`, `registration_cycle`, `registration_answer`, and `promotion_offer`. Use these concurrency keys exactly:

```sql
UNIQUE KEY uk_registration_activity_user (activity_id, user_id)
UNIQUE KEY uk_registration_cycle_number (registration_id, cycle_number)
UNIQUE KEY uk_registration_cycle_waitlist_sequence (position_id, waitlist_sequence)
UNIQUE KEY uk_registration_answer_cycle_scope_question
    (registration_cycle_id, question_scope, question_id)
UNIQUE KEY uk_promotion_offer_cycle (registration_cycle_id)
```

- [ ] **Step 3: Run the focused contract test**

Expected: still RED because V7 and V8 are intentionally absent.

---

### Task 4: Create collaboration and outbox migrations

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__create_collaboration_tables.sql`
- Create: `backend/src/main/resources/db/migration/V8__create_outbox_table.sql`

**Interfaces:**
- Consumes: spec sections 3.6 and 3.7.
- Produces: check-in, notification, append-only audit, and future transactional-outbox storage.

- [ ] **Step 1: Implement V7**

Create `checkin_session`, `checkin_record`, `notification`, and `audit_log`. `audit_log` has no `update_time`. Enforce `uk_checkin_record_activity_user` and `uk_notification_user_deduplication`; store only static check-in code hashes in MySQL.

- [ ] **Step 2: Implement V8**

Create `outbox_event` with `uk_outbox_event_event_id`, status/retry scheduling index, and aggregate lookup index. Do not create a Kafka producer or relay.

- [ ] **Step 3: Run the contract test and confirm GREEN**

Run the Task 1 command. Expected: PASS with all 22 domain tables found and no physical foreign-key syntax.

- [ ] **Step 4: Commit the database deliverable**

```powershell
git add backend/src/main/resources/db/migration backend/src/test/java/com/volunteerflow/infrastructure/persistence/FlywayMigrationContractTest.java
git commit -m "feat: add core MySQL schema migrations"
```

---

### Task 5: Add the optional Redis foundation

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisKeyProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisKeyFactory.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/redis/RedisFoundationTest.java`

**Interfaces:**
- Consumes: `spring.data.redis.*` and `volunteerflow.redis.*` configuration.
- Produces: `StringRedisTemplate` through Boot auto-configuration and `String RedisKeyFactory.key(String module, String businessKey)`.

- [ ] **Step 1: Write the failing Redis test**

The test loads a Spring context without a reachable Redis server, asserts a `StringRedisTemplate` bean exists, and checks:

```java
assertThat(factory.key("activity", "detail:123"))
        .isEqualTo("volunteerflow:test:activity:detail:123");
assertThatThrownBy(() -> factory.key(" ", "123"))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: Run the test and confirm RED**

Expected: compilation failure because Redis classes and the key factory do not exist.

- [ ] **Step 3: Add dependency and implementation**

Add `spring-boot-starter-data-redis`. Bind immutable `RedisKeyProperties` at prefix `volunteerflow.redis` with `environment`, validate nonblank segments in `RedisKeyFactory`, and join keys with `:` using prefix `volunteerflow`.

- [ ] **Step 4: Configure Redis and health groups**

Configure host, port, password, database, connect timeout, and command timeout from the exact environment variables in the spec. Enable probes; define readiness as `readinessState,db` and dependencies as `db,redis`. Do not execute Redis commands during bean construction.

- [ ] **Step 5: Run the Redis test and full suite**

Expected: Redis test passes without a Redis server, followed by all Maven tests passing.

- [ ] **Step 6: Commit the Redis deliverable**

```powershell
git add backend/pom.xml backend/src/main backend/src/test backend/README.md
git commit -m "feat: add optional Redis foundation"
```

---

### Task 6: Secure local configuration and apply migrations

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.gitignore`
- Create locally only: `backend/src/main/resources/application-local.yml`
- Modify: `backend/.env.example`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: the user's currently configured MySQL development connection.
- Produces: a migrated MySQL schema and a tracked configuration free of credentials.

- [ ] **Step 1: Preflight the configured database read-only**

Verify TCP reachability, MySQL server version, current database name, existing tables, existing `flyway_schema_history`, and table-name conflicts. Do not run Flyway if unmanaged tables conflict with V1-V8.

- [ ] **Step 2: Apply Flyway migrations**

Start the application or invoke Flyway with the configured connection. Expected: successful V1-V8 history, unless V1 was already applied, in which case only pending migrations run.

- [ ] **Step 3: Verify the live schema**

Query `information_schema.tables`, `information_schema.statistics`, and `information_schema.referential_constraints`. Expected: all 23 tables including `schema_metadata`, required unique/index names, and zero physical foreign keys.

- [ ] **Step 4: Protect local credentials**

Move environment-specific values to ignored `application-local.yml` or IDEA environment variables. Restore tracked `application.yml` to `${DB_*}` and `${REDIS_*}` placeholders. Confirm `git diff --cached` contains no credential values.

- [ ] **Step 5: Update documentation**

Document the local profile, environment variables, migration command, Redis optionality, health endpoints, and the actual verification boundary. Do not claim Redis connectivity unless a Redis server was reached.

- [ ] **Step 6: Commit configuration and documentation**

```powershell
git add .gitignore backend/.env.example backend/README.md backend/src/main/resources/application.yml
git commit -m "docs: document MySQL and Redis local setup"
```

---

### Task 7: Final verification

**Files:**
- Verify all files changed by Tasks 1-6.

**Interfaces:**
- Consumes: complete implementation tree and migrated development schema.
- Produces: fresh evidence for handoff.

- [ ] **Step 1: Run clean tests**

```powershell
.\backend\mvnw.cmd -f backend\pom.xml clean test -ntp
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Build the executable JAR**

```powershell
.\backend\mvnw.cmd -f backend\pom.xml package -DskipTests -ntp
```

Expected: `backend/target/volunteerflow-backend-0.0.1-SNAPSHOT.jar` exists.

- [ ] **Step 3: Check repository hygiene**

Run `git diff --check`, inspect `git status --short`, and scan tracked changes for password, token, and API-key assignments. Expected: no secrets and no unrelated user files staged.

- [ ] **Step 4: Report exact external verification**

Report the applied Flyway versions, live table count, physical foreign-key count, Redis connectivity status, test count, build result, and any unverified item without inventing results.
