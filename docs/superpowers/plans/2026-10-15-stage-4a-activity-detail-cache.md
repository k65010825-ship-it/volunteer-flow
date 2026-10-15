# Stage 4A Activity Detail Cache Implementation Plan

> Execute inline under `superpowers:executing-plans`; every production behavior follows RED-GREEN-REFACTOR.

**Spec:** `docs/superpowers/specs/2026-10-15-stage-4a-activity-detail-cache-design.md`

**Goal:** Add an optional Redis Cache Aside path for published activity details with RBAC preserved and post-commit invalidation on every relevant mutation.

**Global constraints:** Preserve the three existing user-owned frontend modifications byte-for-byte. Do not change existing MySQL or Redis endpoint settings. Do not make Redis a readiness dependency or a registration correctness dependency.

## Task 1: Cache document, configuration, and resilient gateway

**Files:**
- Create `backend/src/main/java/com/volunteerflow/activity/ActivityDetailCache.java`
- Create `backend/src/main/java/com/volunteerflow/activity/ActivityDetailCacheProperties.java`
- Create `backend/src/test/java/com/volunteerflow/activity/ActivityDetailCacheTest.java`
- Modify `backend/src/main/resources/application.yml`

**Interfaces:** Produces `Optional<ActivityService.ActivityDetail> get(Long activityId)`, `void put(ActivityDetail detail)`, and `void evict(Long activityId)`. Uses `StringRedisTemplate`, application `ObjectMapper`, `RedisKeyFactory`, explicit schema version `1`, five-minute TTL, and up-to-sixty-second jitter.

1. Write tests for round-trip serialization, key shape, TTL range, unsupported/corrupt documents, non-published write rejection, and Redis read/write/delete exceptions.
2. Run `./mvnw.cmd -Dtest=ActivityDetailCacheTest test`; expect compilation/test failure because the cache types do not exist.
3. Implement the minimal properties and gateway.
4. Rerun the focused test; expect success.
5. Commit only Task 1 files.

## Task 2: Cache Aside read path without RBAC bypass

**Files:**
- Modify `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Modify `backend/src/test/java/com/volunteerflow/activity/ActivityServiceTest.java`

**Interfaces:** Consumes Task 1 `ActivityDetailCache`. Cache hits authorize membership from cached organization ID. Cache misses retain current MySQL authorization and only populate published results.

1. Add failing tests for hit, miss, Redis-degraded miss, draft non-caching, and unchanged management detail.
2. Run focused tests; expect failures caused by the absent Cache Aside integration.
3. Implement the minimal read integration and constructor dependency.
4. Rerun focused tests; expect success.
5. Commit only Task 2 files.

## Task 3: Transaction-aware invalidation

**Files:**
- Create `backend/src/main/java/com/volunteerflow/activity/ActivityDetailCacheInvalidator.java`
- Create `backend/src/test/java/com/volunteerflow/activity/ActivityDetailCacheInvalidatorTest.java`
- Modify `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Modify `backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java`
- Modify `backend/src/main/java/com/volunteerflow/checkin/ActivityFinalizationService.java`
- Modify their focused test classes and constructor call sites.

**Interfaces:** Produces `void evictAfterCommit(Long activityId)`. If synchronization is active, register one `afterCommit` callback; otherwise evict immediately. Consumes Task 1 `evict`.

1. Add failing tests for commit/rollback timing and each mutation path: publication, cancellation, published activity change, published position change/publication, and finalization.
2. Run focused tests; expect failures because invalidation is absent.
3. Inject the invalidator and schedule eviction only after successful writes.
4. Rerun focused tests; expect success.
5. Commit only Task 3 files.

## Task 4: Configuration contract and full verification

**Files:**
- Modify `backend/src/test/java/com/volunteerflow/infrastructure/redis/RedisFoundationTest.java` if needed.
- Modify `README.md` or deployment documentation only if a cache-specific environment variable table already exists.

**Interfaces:** Consumes Tasks 1-3. No frontend or database migration changes.

1. Add a configuration-binding assertion proving defaults load without contacting Redis.
2. Run the focused Redis/configuration tests; expect RED before the assertion's production binding exists, then GREEN.
3. Run `./mvnw.cmd test`; expect all backend tests to pass without a running Redis server.
4. Inspect `git diff --check` and verify the three user-owned frontend diffs are unchanged.
5. Commit Task 4 files, package the branch diff, and perform one fresh-context final review.

## Review Focus

- Cached content must not bypass membership checks or expose draft/management data.
- Redis exceptions and corrupt payloads must not alter endpoint availability.
- Cache deletion must occur after commit, never before a transaction that can roll back.
- Every mutation that changes `ActivityDetail` must invalidate exactly the affected activity key.
- Logs must not contain Redis credentials or full cached payloads.
- Registration/waitlist correctness must remain MySQL-only.
