# Stage 4C Redis Rate Limits Design

**Date:** 2026-10-16  
**Status:** Draft for written-spec review  
**Scope:** Login, activity registration, promotion-offer response, and self check-in rate limits

## 1. Goal

Stage 4C adds abuse protection to the existing HTTP command boundaries without moving business
correctness into Redis. Redis provides the shared rate-limit state when it is available. Existing
MySQL transactions, row locks, and unique constraints remain the authority for registration,
promotion, and check-in correctness.

The stage succeeds when:

- repeated protected commands are rejected with HTTP `429` and a useful `Retry-After` header;
- multiple backend instances share the same Redis counters;
- Redis failure does not stop registration, promotion responses, or check-in;
- login retains bounded single-instance protection while Redis is unavailable;
- rate-limit keys and logs do not disclose raw usernames, IP addresses, codes, or tokens; and
- all behavior is configurable and covered by deterministic automated tests.

## 2. Scope

### 2.1 Protected commands

| Action | HTTP endpoint | Subjects | Default policy |
|---|---|---|---|
| Login | `POST /api/v1/auth/login` | client IP and normalized username | IP: 20/minute; username: 5/5 minutes |
| Activity registration | `POST /api/v1/activities/{activityId}/registrations` | user and activity | 3/10 seconds |
| Promotion response | `POST /api/v1/promotion-offers/{offerId}/responses` | user and offer | 5/minute |
| Static self check-in | `POST /api/v1/check-in-sessions/{sessionId}/check-ins` | user and session | 5/minute |
| Dynamic self check-in | `POST /api/v1/check-in-sessions/{sessionId}/dynamic-check-ins` | user and session | 10/minute |

Every accepted attempt consumes one permit before entering the domain service. This deliberately
limits repeated valid commands as well as invalid commands. Domain idempotency and conflicts still
produce their existing responses after a permit is granted.

The login username subject is normalized by trimming surrounding whitespace and applying
`toLowerCase(Locale.ROOT)`. This normalization is intentionally more conservative than the current
case-sensitive authentication lookup: whitespace and casing variations cannot create unlimited
rate-limit subjects. A blank value cannot reach the guard because Jakarta validation runs before
the controller body.

The defaults are starting values for project testing, not claimed production capacity. Each limit
and window is configurable through typed Spring configuration and environment variables.

### 2.2 Out of scope

- account creation, refresh-token, logout, read-only query, manager review, and manual check-in
  limits;
- Redis distributed locks or Redis-based registration inventory;
- token-bucket, leaky-bucket, or sliding-window algorithms;
- CAPTCHA, account lockout, WAF rules, and device fingerprinting;
- k6 performance acceptance and the 100-user/20-place report; and
- Stage 4A cache stampede coordination.

Distributed registration assistance and k6 acceptance remain Stage 4D. Kafka and Outbox remain
Stage 5.

## 3. Chosen approach

### 3.1 Alternatives considered

1. **Redis fixed-window counter with Lua — selected.** It is small, atomic, easy to inspect, and
   teaches the Redis primitives this project is intended to demonstrate. Its known boundary burst
   is acceptable for abuse protection because MySQL remains the correctness boundary.
2. **Redis sorted-set sliding window.** It provides more precise windows but performs more Redis
   work per request and retains one entry per attempt. That cost is unnecessary at this scale.
3. **Token bucket through Redisson or Bucket4j.** It provides smoother traffic but adds another
   abstraction and dependency before the project has evidence that burst smoothing is needed.

### 3.2 Atomic Redis operation

One Lua script performs the complete decision:

1. increment the current fixed-window key;
2. when the value becomes `1`, set its expiration to the remaining window duration plus one second;
3. read the key TTL;
4. return the current count and remaining TTL.

The Java adapter decides `allowed = count <= limit` and calculates `retryAfterSeconds` from the
returned TTL. The increment and expiration are never separate client round trips, so a crash cannot
leave a persistent counter without a TTL.

The current window is calculated from an injected `Clock`:

```text
windowStart = floor(epochMillis / windowMillis) * windowMillis
```

The Redis key includes `windowStart`. All application instances therefore address the same counter
for the same subject and window. The script receives the calculated TTL rather than reading server
time; modest clock skew can only shift a request near a window boundary and cannot affect business
correctness.

## 4. Components and boundaries

### 4.1 `RateLimitProperties`

A typed `@ConfigurationProperties("volunteerflow.rate-limit")` record owns:

- `enabled`;
- two login subject policies plus registration, promotion-response, static-checkin, and
  dynamic-checkin policies;
- Redis command timeout inherited from the existing Spring Redis configuration;
- the HMAC key used to pseudonymize subjects; and
- local login fallback capacity and cleanup settings.

Startup validation rejects non-positive limits/windows, windows shorter than one second, a local
fallback capacity smaller than the login limits, and an HMAC key shorter than 32 bytes.

The deployment contract introduces `RATE_LIMIT_KEY_SECRET`. It is separate from `JWT_SECRET` so
rotation or disclosure of one purpose does not automatically compromise the other. Test profile
configuration supplies a non-production test value.

### 4.2 `RateLimitSubjectHasher`

The hasher produces lowercase Base64URL HMAC-SHA-256 values using a purpose-prefixed input such as:

```text
volunteerflow-rate-limit-v1\0login-username\0alice
```

Plain SHA-256 is not sufficient because usernames and IP addresses have small searchable domains.
Raw subjects must not appear in Redis keys, exception messages, or warning logs.

Authenticated business keys use stable numeric IDs but still pass through the same hasher, keeping
the key format consistent and preventing casual disclosure of user activity through Redis key
inspection.

### 4.3 `RedisFixedWindowRateLimiter`

This infrastructure adapter owns `StringRedisTemplate`, the Lua script, `RedisKeyFactory`, and the
injected `Clock`. It accepts an action, opaque subject, limit, and window and returns:

```java
record RateLimitDecision(boolean allowed, long retryAfterSeconds) {}
```

Redis keys follow the existing environment-aware convention:

```text
volunteerflow:<environment>:rate-limit:<action>:<subject-hmac>:<window-start>
```

The adapter translates Redis connection, timeout, serialization, and script failures into a
single internal `RateLimitStoreUnavailableException`. It never logs the subject or constructed key.

### 4.4 `LocalLoginRateLimiter`

The login-only fallback uses an in-memory fixed-window map. It has two independently checked
subjects: client IP and normalized username. The map is capacity-bounded; expired entries are
removed during periodic opportunistic cleanup, and if capacity is reached, expired entries are
removed before the oldest remaining entry is evicted.

This fallback is deliberately instance-local. It provides basic protection during Redis outages but
does not claim cluster-wide enforcement. It is not used for registration, promotion, or check-in.

### 4.5 `RateLimitGuard`

The application-facing guard exposes explicit, typed methods rather than an annotation/SpEL system:

```java
void checkLogin(String clientAddress, String normalizedUsername);
void checkRegistration(long userId, long activityId);
void checkPromotionResponse(long userId, long offerId);
void checkStaticCheckin(long userId, long sessionId);
void checkDynamicCheckin(long userId, long sessionId);
```

Explicit methods make the protected subjects reviewable at each HTTP boundary and avoid fragile
path-expression parsing. Controllers call the guard after request validation and authentication but
before domain services or database transactions.

For login, Redis success is authoritative for that request. On Redis failure, both login policies
are evaluated by the local fallback. For business actions, Redis failure is logged once at WARN with
the action and exception class, then the request proceeds.

## 5. HTTP contract

### 5.1 Rejection response

Rate-limit rejection throws a dedicated `RateLimitExceededException`. The global exception handler
returns:

- status: `429 Too Many Requests`;
- `Content-Type: application/problem+json`;
- `Retry-After: <whole seconds>` with a minimum of `1`;
- problem property `code: RATE_LIMITED`;
- the existing request ID property; and
- a generic detail that does not identify which subject key was exhausted.

The response does not disclose counts, configured limits, usernames, addresses, activity IDs,
session IDs, offer IDs, check-in codes, or dynamic credentials.

### 5.2 Client address contract

Application code reads the effective `HttpServletRequest.getRemoteAddr()` value and never parses an
arbitrary client-provided `X-Forwarded-For` header itself.

For the provided deployment:

- Nginx overwrites `X-Real-IP` and `X-Forwarded-For` with `$remote_addr`, rather than appending an
  untrusted incoming chain;
- Nginx remains the only publicly exposed service;
- the backend enables the container-appropriate native forwarded-header strategy; and
- direct local backend access naturally uses the socket peer address.

If the backend is later exposed behind a different proxy topology, its trusted-proxy configuration
must be updated before IP-based limits are considered reliable.

## 6. Failure behavior

| Failure | Login | Registration, promotion, check-in |
|---|---|---|
| Redis unavailable before decision | Evaluate bounded local fallback | Fail open and continue to existing domain service |
| Redis reply malformed | Evaluate bounded local fallback | Fail open and continue |
| Request exceeds active limit | Return `429` | Return `429` |
| MySQL/domain conflict after permit | Preserve existing response | Preserve existing response |
| Local fallback capacity reached | Evict safely, then evaluate | Not applicable |

No Redis exception crosses the controller boundary as a `500`. Fail-open does not mean correctness
is weakened: the current MySQL transactions, locks, and uniqueness rules still decide whether a
business command is valid.

The guard logs only action, fallback mode, request ID supplied by MDC, and exception class. It never
logs raw subjects or credentials. Repeated outages should be observable without producing one stack
trace per request; the implementation uses concise warning messages and leaves metric integration
for Stage 6.

## 7. Configuration

Suggested property shape:

```yaml
volunteerflow:
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    key-secret: ${RATE_LIMIT_KEY_SECRET:}
    login:
      ip: { limit: ${RATE_LIMIT_LOGIN_IP_LIMIT:20}, window: ${RATE_LIMIT_LOGIN_IP_WINDOW:1m} }
      username: { limit: ${RATE_LIMIT_LOGIN_USERNAME_LIMIT:5}, window: ${RATE_LIMIT_LOGIN_USERNAME_WINDOW:5m} }
    registration: { limit: ${RATE_LIMIT_REGISTRATION_LIMIT:3}, window: ${RATE_LIMIT_REGISTRATION_WINDOW:10s} }
    promotion-response: { limit: ${RATE_LIMIT_PROMOTION_LIMIT:5}, window: ${RATE_LIMIT_PROMOTION_WINDOW:1m} }
    static-checkin: { limit: ${RATE_LIMIT_STATIC_CHECKIN_LIMIT:5}, window: ${RATE_LIMIT_STATIC_CHECKIN_WINDOW:1m} }
    dynamic-checkin: { limit: ${RATE_LIMIT_DYNAMIC_CHECKIN_LIMIT:10}, window: ${RATE_LIMIT_DYNAMIC_CHECKIN_WINDOW:1m} }
    local-login:
      max-entries: ${RATE_LIMIT_LOCAL_LOGIN_MAX_ENTRIES:10000}
      cleanup-interval: ${RATE_LIMIT_LOCAL_LOGIN_CLEANUP_INTERVAL:1m}
```

`RATE_LIMIT_ENABLED=false` bypasses all Stage 4C guards and does not require Redis access. The key
secret remains required whenever rate limiting is enabled.

The Compose and `.env.example` files expose these settings without changing the user's existing
MySQL or Redis host/port defaults.

## 8. Test strategy

### 8.1 Unit tests

- configuration accepts defaults and rejects invalid limits, windows, capacities, and secrets;
- subject HMAC is deterministic, purpose-separated, URL-safe, and never contains the raw subject;
- Lua result mapping permits exactly `limit` attempts and rejects the next;
- fixed-window keys change at the boundary and carry a positive TTL;
- Redis failures map to the single internal unavailable exception;
- login uses local fallback only when Redis is unavailable;
- business actions fail open only when Redis is unavailable;
- local fallback is capacity-bounded and expires old windows; and
- logs and exception text do not contain raw usernames, IPs, codes, or tokens.

All time-sensitive tests use an injected mutable/fixed `Clock`; they do not sleep.

### 8.2 Controller and web-contract tests

- every protected endpoint calls the correct guard with the authenticated/path subjects;
- an exceeded limit returns `429`, `RATE_LIMITED`, request ID, and `Retry-After`;
- a rejected request never invokes the domain service;
- an allowed request preserves the endpoint's current status and response body;
- malformed requests still use existing validation responses; and
- the handler never exposes the exhausted subject.

### 8.3 Configuration and deployment contract tests

- Redis infrastructure registers the rate-limit properties and script adapter;
- application configuration exposes every environment override;
- Compose forwards the Stage 4C environment variables;
- Nginx replaces, rather than trusts/appends, the incoming client IP chain; and
- README and the Stage 4 Redis runbook distinguish source verification from live VM evidence.

### 8.4 Verification boundary

Automated tests prove Java behavior, web contracts, configuration, and local frontend regression
safety. They do not prove the user's virtual-machine Redis connectivity, multi-instance sharing,
Nginx client-address behavior, or real traffic capacity. Those claims require a deployed runtime and
belong in the Stage 4D k6 acceptance report.

## 9. Delivery slices

1. Typed configuration, subject HMAC, fixed-window decision model, and Redis Lua adapter.
2. Bounded local login fallback and hybrid failure policy.
3. HTTP `429` contract and explicit guards at all five command endpoints.
4. Proxy/deployment configuration, documentation, and contract tests.
5. Full backend/frontend regression verification and credential-leak review.

Each slice is implemented test-first. No slice changes the existing MySQL transaction model or adds
a physical foreign key.
