# VolunteerFlow Stage 4A Activity Detail Cache Design

**Status:** Approved for implementation  
**Scope:** Cache Aside for `GET /api/v1/activities/{id}` only

## Goal

Reduce repeated MySQL reads for member-visible, published activity details while keeping MySQL as the only source of truth. Redis is an optional acceleration layer: a Redis timeout, connection failure, malformed value, or write failure must not make the activity detail endpoint unavailable.

## Boundaries

- Cache only published activity details and their `ACTIVE` positions.
- Do not cache organization activity lists, draft/management details, registration forms, registration state, remaining capacity, or authorization results.
- Every request still performs organization membership authorization. A cache hit must never bypass RBAC.
- Registration and waitlist decisions continue to use MySQL transactions and row locks; no correctness decision reads this cache.
- Existing Redis host, port, password, database, command timeout, and readiness configuration remain unchanged.

## Cache Contract

- Key: `volunteerflow:<environment>:activity-detail:<activityId>:v1`, built through `RedisKeyFactory`.
- Value: explicit versioned JSON document, not a serialized Java entity graph and not a generic object serializer.
- TTL: five minutes plus a random jitter from zero through sixty seconds.
- Miss: load from MySQL, authorize, return the database result, and best-effort populate Redis only when the activity is `PUBLISHED`.
- Hit: deserialize and validate the document, authorize against its organization identifier, then return the cached detail.
- Invalid or unsupported JSON: treat as a miss and best-effort delete the bad key.
- Redis read/write/delete failure: log a concise warning without credentials or cached payloads, then continue through the MySQL path or complete the database write.
- Do not negative-cache missing activities in this slice.

## Consistency and Invalidation

Database writes commit before cache deletion. If a Spring transaction is active, invalidation is registered with transaction synchronization and runs only after commit; rollback performs no eviction. Outside a transaction, eviction runs immediately.

Evict the activity detail key after successful:

- activity publication;
- activity cancellation;
- published activity change;
- published position change;
- draft position publication on a published activity;
- automatic activity finalization.

Adding an unpublished draft position and changing registration questions do not evict because neither changes the public detail representation. Registration and check-in mutations do not evict because the cached representation contains no mutable occupancy or attendance counts.

This Cache Aside strategy permits a bounded stale-read race if an old database read repopulates after a concurrent post-commit delete. The short TTL limits that window; distributed coordination and stronger anti-stampede behavior remain deferred to Stage 4D.

## Configuration

Add only cache-specific settings:

```yaml
volunteerflow:
  cache:
    activity-detail:
      ttl: ${ACTIVITY_DETAIL_CACHE_TTL:5m}
      max-jitter: ${ACTIVITY_DETAIL_CACHE_MAX_JITTER:60s}
```

Both durations must be positive for TTL and non-negative for jitter. These settings do not replace or rewrite the existing virtual-machine Redis endpoint.

## Verification

Automated tests must demonstrate:

- cache hit avoids activity/position mapper reads but still performs membership authorization;
- cache miss reads MySQL and populates only a published detail;
- Redis read, deserialize, write, and delete failures degrade safely;
- TTL stays within the configured base-plus-jitter range;
- invalidation waits for commit and does not run after rollback;
- each public-detail mutation path schedules the correct activity key eviction;
- draft and management detail behavior remains unchanged;
- the full backend suite remains green without a running Redis server.

Live Redis hit-rate or latency improvement is explicitly not claimed by this slice; that requires a later environment-backed verification.
