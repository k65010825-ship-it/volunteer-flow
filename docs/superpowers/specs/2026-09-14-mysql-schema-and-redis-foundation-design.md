# VolunteerFlow MySQL Schema and Redis Foundation Design

## 1. Goal and scope

This change completes the first-version MySQL schema required by the approved VolunteerFlow product design and introduces Redis as an optional infrastructure dependency. MySQL remains the sole source of truth. Redis is prepared only for later caching, rate limiting, dynamic check-in codes, countdown assistance, and distributed auxiliary locking; none of those business features are implemented in this change.

Kafka remains out of scope. The `outbox_event` table is created now so later event publishing does not require restructuring core transactions.

## 2. Global database rules

- Target database: MySQL 8, `utf8mb4` character set.
- Schema changes are applied only through Flyway.
- Existing `V1__create_schema_metadata.sql` is not modified because it may already have been applied.
- New migrations are split by domain from V2 through V8.
- Application IDs use MyBatis-Plus `ASSIGN_ID` and are stored as signed `BIGINT` without `AUTO_INCREMENT`.
- Business table names and column names use lower snake case and singular table names.
- Physical foreign keys and cascade operations are prohibited. All `*_id` columns are logical references.
- Every frequently queried logical reference has a normal index.
- Reference existence, resource status, tenant ownership, and delete protection are enforced by application services within transactions.
- Database unique constraints remain the final guard for business uniqueness.
- Mutable aggregate tables use an integer `version` column for optimistic locking where appropriate.
- States and types use `VARCHAR`, not database `ENUM`, so Java and migrations can evolve them explicitly.
- Variable answers, change snapshots, and event payloads use MySQL `JSON`.
- Core business data is disabled, canceled, expired, or revoked instead of physically deleted.
- Organization-owned records explicitly carry `organization_id`. Platform-level notifications, audits, and events may use a null organization ID.
- Ordinary business code must not update or delete `audit_log` rows.

Unless stated otherwise, business tables contain `id BIGINT NOT NULL`, `create_time DATETIME(6) NOT NULL`, and `update_time DATETIME(6) NOT NULL`, with `id` as the primary key.

## 3. Flyway migration layout

### 3.1 V2: accounts and refresh sessions

`app_user`

- `username VARCHAR(64) NOT NULL`
- `password_hash VARCHAR(100) NOT NULL`
- `real_name VARCHAR(64) NOT NULL`
- `student_number VARCHAR(64) NOT NULL`
- `contact VARCHAR(128) NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `platform_role VARCHAR(32) NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique keys on `username` and `student_number`
- Index on `status`

`refresh_session`

- `user_id BIGINT NOT NULL`
- `token_hash CHAR(64) NOT NULL`
- `device_name VARCHAR(128) NULL`
- `expires_at DATETIME(6) NOT NULL`
- `last_used_at DATETIME(6) NULL`
- `revoked_at DATETIME(6) NULL`
- Unique key on `token_hash`
- Indexes on `user_id` and `expires_at`

No administrator password or fixed refresh token is inserted by a migration.

### 3.2 V3: organizations, invitations, and members

`organization`

- `name VARCHAR(128) NOT NULL`
- `description VARCHAR(1000) NULL`
- `status VARCHAR(32) NOT NULL`
- `created_by BIGINT NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Indexes on `created_by` and `status`

`organization_invite`

- `organization_id BIGINT NOT NULL`
- `default_role_id BIGINT NOT NULL`
- `code_hash CHAR(64) NOT NULL`
- `max_uses INT NOT NULL`
- `used_count INT NOT NULL DEFAULT 0`
- `expires_at DATETIME(6) NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `created_by BIGINT NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `code_hash`
- Indexes on `organization_id`, `default_role_id`, `created_by`, and `(organization_id, status, expires_at)`

`organization_member`

- `organization_id BIGINT NOT NULL`
- `user_id BIGINT NOT NULL`
- `role_id BIGINT NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `joined_at DATETIME(6) NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `(organization_id, user_id)`
- Indexes on `user_id`, `role_id`, and `(organization_id, status)`

Role IDs are logical references even though role tables are created in V4. Organization creation later inserts the organization, three default roles, the owner membership, and the audit record in one transaction.

### 3.3 V4: organization RBAC

`rbac_permission`

- `code VARCHAR(64) NOT NULL`
- `name VARCHAR(64) NOT NULL`
- `description VARCHAR(255) NULL`
- `status VARCHAR(32) NOT NULL`
- Unique key on `code`
- Index on `status`

The migration seeds these stable permission codes:

```text
organization:read
organization:manage
member:read
member:invite
member:role_assign
role:read
role:create
role:update
activity:read
activity:create
activity:publish
activity:change
activity:cancel
registration:create
registration:cancel
registration:review
registration:promote
checkin:create
checkin:manage
notification:read
audit:read
```

`rbac_role`

- `organization_id BIGINT NOT NULL`
- `name VARCHAR(64) NOT NULL`
- `description VARCHAR(255) NULL`
- `built_in_type VARCHAR(32) NOT NULL`, using `OWNER`, `ACTIVITY_ADMIN`, `MEMBER`, or `CUSTOM`
- `protected_role TINYINT(1) NOT NULL DEFAULT 0`
- `status VARCHAR(32) NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `(organization_id, name)`
- Index on `(organization_id, status)`

`rbac_role_permission`

- `organization_id BIGINT NOT NULL`
- `role_id BIGINT NOT NULL`
- `permission_id BIGINT NOT NULL`
- Unique key on `(role_id, permission_id)`
- Indexes on `organization_id` and `permission_id`

The owner role is interpreted by application code as possessing every active permission, including permissions introduced later.

### 3.4 V5: activities, positions, questions, and changes

`activity`

- `organization_id BIGINT NOT NULL`
- `title VARCHAR(160) NOT NULL`
- `description TEXT NOT NULL`
- `location VARCHAR(255) NOT NULL`
- `registration_start_at DATETIME(6) NOT NULL`
- `registration_end_at DATETIME(6) NOT NULL`
- `free_cancel_deadline_at DATETIME(6) NULL`
- `activity_start_at DATETIME(6) NOT NULL`
- `activity_end_at DATETIME(6) NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `created_by BIGINT NOT NULL`
- `published_at DATETIME(6) NULL`
- `canceled_at DATETIME(6) NULL`
- `version INT NOT NULL DEFAULT 0`
- Indexes on `organization_id`, `created_by`, `(organization_id, status)`, and `(organization_id, activity_start_at)`

`activity_position`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `name VARCHAR(128) NOT NULL`
- `description VARCHAR(1000) NOT NULL`
- `capacity INT NOT NULL`
- `registration_mode VARCHAR(32) NOT NULL`
- `promotion_timeout_minutes INT NOT NULL DEFAULT 120`
- `service_start_at DATETIME(6) NULL`
- `service_end_at DATETIME(6) NULL`
- `meeting_location VARCHAR(255) NULL`
- `next_waitlist_sequence BIGINT NOT NULL DEFAULT 1`
- `status VARCHAR(32) NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `(activity_id, name)`
- Indexes on `organization_id` and `(activity_id, status)`

The position row is locked during capacity allocation and waitlist sequence allocation. `next_waitlist_sequence` is an allocation counter, not a cached participant count.

`activity_question`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `question_type VARCHAR(32) NOT NULL`
- `title VARCHAR(255) NOT NULL`
- `required_question TINYINT(1) NOT NULL DEFAULT 0`
- `options_json JSON NULL`
- `sort_order INT NOT NULL`
- Unique key on `(activity_id, sort_order)`
- Index on `organization_id`

`activity_position_question`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `position_id BIGINT NOT NULL`
- Same question fields as `activity_question`
- Unique key on `(position_id, sort_order)`
- Indexes on `organization_id` and `activity_id`

`activity_change`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `target_type VARCHAR(32) NOT NULL`
- `target_id BIGINT NOT NULL`
- `change_type VARCHAR(64) NOT NULL`
- `before_json JSON NOT NULL`
- `after_json JSON NOT NULL`
- `reason VARCHAR(500) NOT NULL`
- `changed_by BIGINT NOT NULL`
- Indexes on `organization_id`, `activity_id`, `target_id`, `changed_by`, and `(activity_id, create_time)`

### 3.5 V6: registrations, cycles, answers, and promotion offers

`registration`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `user_id BIGINT NOT NULL`
- `last_cycle_number INT NOT NULL DEFAULT 0`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `(activity_id, user_id)`
- Indexes on `organization_id`, `user_id`, and `(organization_id, user_id)`

`registration_cycle`

- `organization_id BIGINT NOT NULL`
- `registration_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `position_id BIGINT NOT NULL`
- `user_id BIGINT NOT NULL`
- `cycle_number INT NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `waitlist_sequence BIGINT NULL`
- `submitted_at DATETIME(6) NOT NULL`
- `reviewed_by BIGINT NULL`
- `reviewed_at DATETIME(6) NULL`
- `review_reason VARCHAR(500) NULL`
- `canceled_at DATETIME(6) NULL`
- `cancel_reason VARCHAR(500) NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `(registration_id, cycle_number)`
- Unique key on `(position_id, waitlist_sequence)`; MySQL permits multiple null values, so non-waitlisted cycles are unaffected
- Indexes on `organization_id`, `activity_id`, `user_id`, `(position_id, status)`, and `(registration_id, status)`

`registration_answer`

- `organization_id BIGINT NOT NULL`
- `registration_cycle_id BIGINT NOT NULL`
- `question_scope VARCHAR(32) NOT NULL`
- `question_id BIGINT NOT NULL`
- `answer_json JSON NOT NULL`
- Unique key on `(registration_cycle_id, question_scope, question_id)`
- Indexes on `organization_id` and `question_id`

`promotion_offer`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `position_id BIGINT NOT NULL`
- `registration_cycle_id BIGINT NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `expires_at DATETIME(6) NOT NULL`
- `responded_at DATETIME(6) NULL`
- `created_by BIGINT NULL`
- `reason VARCHAR(500) NULL`
- `version INT NOT NULL DEFAULT 0`
- Unique key on `registration_cycle_id`
- Indexes on `organization_id`, `activity_id`, `position_id`, and `(status, expires_at)`

Each cycle can receive at most one promotion offer because declining or expiring an offer terminates that cycle. A new attempt creates a new cycle.

### 3.6 V7: check-in, notifications, and audit

`checkin_session`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `session_type VARCHAR(32) NOT NULL`
- `static_code_hash CHAR(64) NULL`
- `status VARCHAR(32) NOT NULL`
- `opens_at DATETIME(6) NOT NULL`
- `closes_at DATETIME(6) NULL`
- `created_by BIGINT NOT NULL`
- `version INT NOT NULL DEFAULT 0`
- Indexes on `organization_id`, `activity_id`, and `(activity_id, status)`

Dynamic code values are never persisted in MySQL; only static code hashes may be stored here.

`checkin_record`

- `organization_id BIGINT NOT NULL`
- `activity_id BIGINT NOT NULL`
- `checkin_session_id BIGINT NOT NULL`
- `registration_cycle_id BIGINT NOT NULL`
- `user_id BIGINT NOT NULL`
- `checkin_method VARCHAR(32) NOT NULL`
- `checked_in_at DATETIME(6) NOT NULL`
- `operator_id BIGINT NULL`
- `manual_reason VARCHAR(500) NULL`
- `status VARCHAR(32) NOT NULL`
- Unique key on `(activity_id, user_id)`
- Indexes on `organization_id`, `checkin_session_id`, `registration_cycle_id`, and `user_id`

`notification`

- `organization_id BIGINT NULL`
- `user_id BIGINT NOT NULL`
- `notification_type VARCHAR(64) NOT NULL`
- `title VARCHAR(160) NOT NULL`
- `content TEXT NOT NULL`
- `business_type VARCHAR(64) NULL`
- `business_id BIGINT NULL`
- `deduplication_key VARCHAR(128) NOT NULL`
- `read_at DATETIME(6) NULL`
- Unique key on `(user_id, deduplication_key)`
- Indexes on `organization_id`, `business_id`, and `(user_id, read_at, create_time)`

`audit_log`

- `organization_id BIGINT NULL`
- `actor_user_id BIGINT NULL`
- `action VARCHAR(96) NOT NULL`
- `resource_type VARCHAR(64) NOT NULL`
- `resource_id BIGINT NULL`
- `before_json JSON NULL`
- `after_json JSON NULL`
- `reason VARCHAR(500) NULL`
- `request_id VARCHAR(64) NULL`
- `ip_address VARCHAR(64) NULL`
- Only `create_time` is present; there is no `update_time`
- Indexes on `organization_id`, `actor_user_id`, `(resource_type, resource_id)`, `request_id`, and `create_time`

### 3.7 V8: transactional outbox reservation

`outbox_event`

- `organization_id BIGINT NULL`
- `event_id CHAR(36) NOT NULL`
- `aggregate_type VARCHAR(64) NOT NULL`
- `aggregate_id VARCHAR(64) NOT NULL`
- `event_type VARCHAR(96) NOT NULL`
- `payload_json JSON NOT NULL`
- `status VARCHAR(32) NOT NULL`
- `retry_count INT NOT NULL DEFAULT 0`
- `next_retry_at DATETIME(6) NULL`
- `published_at DATETIME(6) NULL`
- Unique key on `event_id`
- Indexes on `organization_id`, `(status, next_retry_at)`, and `(aggregate_type, aggregate_id)`

Kafka producers and relays are not introduced by this change.

## 4. Redis foundation

### 4.1 Client and serialization boundary

- Add `spring-boot-starter-data-redis` and use its default Lettuce client.
- Do not add Redisson until a real distributed lock use case is implemented.
- Use the auto-configured `StringRedisTemplate`; keys and values are UTF-8 strings.
- Do not provide a generic `RedisTemplate<String, Object>`. Business caches later serialize explicit, versioned cache DTOs to JSON with the application `ObjectMapper`.
- Do not enable `@Cacheable`, create locks, implement rate limits, or store dynamic check-in codes in this change.

### 4.2 Configuration

Standard Spring properties bind from:

```text
REDIS_HOST                default localhost
REDIS_PORT                default 6379
REDIS_PASSWORD            no default secret
REDIS_DATABASE            default 0
REDIS_CONNECT_TIMEOUT     default 1s
REDIS_COMMAND_TIMEOUT     default 2s
```

The application defines an environment name and Redis key prefix. All future keys follow:

```text
volunteerflow:{environment}:{module}:{business-key}
```

A small `RedisKeyFactory` owns this format so business modules do not concatenate keys independently.

### 4.3 Failure and health boundaries

- Application startup does not perform a Redis command; an unavailable Redis server must not prevent the Spring context from starting.
- MySQL remains required and remains the source of truth.
- The readiness health group includes application readiness and database health, not Redis health.
- A dependencies health group exposes both database and Redis contributors for diagnostics.
- Future cache failures fall back to MySQL.
- Future registration lock failures fall back to the MySQL transaction and row-lock path.
- Future dynamic check-in code failures disable dynamic scanning only; manual check-in remains available.

## 5. Local configuration and secrets

The tracked `application.yml` must contain environment-variable placeholders rather than real database or Redis credentials. Existing local database values are used for migration verification, then preserved only in a Git-ignored local configuration or IDEA environment variables. A committed example file documents variable names with non-secret placeholder values.

No password, token, connection credential, or fixed administrator credential is committed.

## 6. Verification strategy

### 6.1 Static migration tests

Automated tests inspect every migration and verify:

- Expected V2 through V8 files exist.
- Every approved table is created exactly once.
- No migration contains `FOREIGN KEY`, `REFERENCES`, cascade operations, or fixed administrator credentials.
- Required unique keys and high-value indexes are present.
- Migration versions remain ordered and V1 remains unchanged.

### 6.2 Spring and Redis tests

- Configuration binding creates a `StringRedisTemplate`.
- `RedisKeyFactory` generates the approved prefix and rejects blank key segments.
- The Spring test context starts when Redis is unreachable because no startup command depends on Redis.
- The readiness group excludes Redis while the dependency group declares Redis.

### 6.3 MySQL verification

Use the configured MySQL 8 development database after a read-only preflight identifies the current schema and Flyway history. If it is safe to migrate, run Flyway and verify:

- V1 through V8 are successful in `flyway_schema_history`.
- All approved tables exist.
- Unique keys and normal indexes match the migration design.
- No physical foreign keys exist.
- Re-running migration is idempotent and reports the schema as current.

If the target schema contains unmanaged or conflicting tables, stop before making changes and report the conflict instead of overwriting data.

### 6.4 Build verification

Run a clean Maven test and package build with Java 17. A successful result must show zero failed tests and produce the executable Spring Boot JAR.

## 7. Acceptance criteria

- V2 through V8 create all first-version core tables through Flyway.
- Schema uses logical references only and contains no physical foreign keys or cascade operations.
- Business uniqueness and waitlist sequence uniqueness are protected by database unique keys.
- Redis dependency, configuration, key convention, health reporting, and optional failure boundary are present.
- No current business path requires Redis.
- Secrets are absent from tracked files and Git history introduced by this change.
- Static migration tests, Spring tests, clean build, and safe MySQL verification pass, or any unavailable external verification is explicitly reported as unverified.
