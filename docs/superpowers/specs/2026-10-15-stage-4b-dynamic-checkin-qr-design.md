# VolunteerFlow Stage 4B Dynamic Check-in QR Design

**Status:** Draft for written-spec review
**Scope:** Redis-backed rotating QR credentials for an open check-in session

## Goal

Replace the ordinary shared code as the default on-site self-check-in experience with a short-lived, rotating QR credential. Redis supplies only the temporary credential and its TTL. MySQL remains the source of truth for session state, authorization, registrations, attendance records, uniqueness, and audit data.

Stage 4B succeeds when a manager can open a dynamic session, display a QR code that rotates automatically, and a confirmed member can scan it to check in once. A Redis outage must not corrupt attendance or block session closure and manual check-in.

## Boundaries

- Add dynamic QR issuance and dynamic self-check-in only.
- Preserve the Stage 3 static-code endpoint as an explicit compatibility path; it is not the automatic Redis-failure fallback.
- Keep manual check-in available when Redis is unavailable.
- Do not add login, registration, invitation, or static-code rate limits in this slice; those belong to Stage 4C.
- Do not add a registration distributed lock, cache anti-stampede coordination, or k6 acceptance suite in this slice; those belong to Stage 4D.
- Do not change the configured MySQL or Redis host, port, password, or database.
- Do not make Redis part of application readiness, because MySQL-backed core flows must remain available without it.

## Chosen Approach

Use a random opaque token per session and 30-second time window, stored in Redis with a 45-second TTL. The additional 15 seconds permit a QR scanned immediately before rotation to complete without accepting a long-lived credential.

Alternatives were rejected:

- A stateless HMAC time code would remain available without Redis, but would not exercise or demonstrate Redis TTL behavior required by Stage 4.
- Persisting every rotation in MySQL would create unnecessary writes and couple a presentation credential to the durable business model.
- Generating a different token on every manager poll would make concurrent displays inconsistent and create needless Redis keys.

## Session Model and Compatibility

`checkin_session.session_type` already supports string values and `static_code_hash` is nullable, so no schema migration is required.

- `DYNAMIC_QR`: explicitly requested by the updated management UI; `static_code_hash` remains null.
- `STATIC_CODE`: the existing Stage 3 behavior; a digest is stored and the existing self-check-in endpoint remains valid.

`OpenSessionRequest` gains an optional `sessionType`. The backend accepts only `DYNAMIC_QR` and `STATIC_CODE`. The updated frontend requests `DYNAMIC_QR`; omitted values continue to default to `STATIC_CODE` so existing API clients do not silently lose the plaintext code response.

Opening, closing, and querying a session continue to use MySQL transactions, the existing activity-first lock order, RBAC permission `checkin:manage`, audit records, and notifications.

## Redis Contract

### Keys

All keys are created through `RedisKeyFactory` and retain the environment namespace.

```text
volunteerflow:<environment>:dynamic-checkin:<sessionId>:<windowEpochSecond>:v1
```

The value is a Base64URL token generated from 24 cryptographically secure random bytes. The token is never logged, audited, persisted to MySQL, or included in exception messages.

### Rotation and TTL

- Window length: 30 seconds.
- Credential lifetime: until 45 seconds after the window start.
- Window identifier: the current UTC epoch second rounded down to a 30-second boundary.
- The manager endpoint returns the same token for the same session and window.
- Creation uses `SET NX` with an expiry equal to `window start + 45 seconds`, followed by a read of the winning value, so concurrent manager screens converge on one token without a distributed Java lock. A credential first requested late in its window receives only the remaining lifetime, never a fresh 45 seconds.
- A submitted credential names its window. The verifier reads that exact key, allowing the previous window only while Redis TTL and the returned expiry permit it.
- Configuration properties validate positive window and TTL durations and require TTL to be greater than the window duration.

## API Contracts

### Open a session

```http
POST /api/v1/activities/{activityId}/check-in-sessions
```

```json
{
  "durationMinutes": 15,
  "sessionType": "DYNAMIC_QR"
}
```

For a dynamic session, the response contains session metadata and no reusable plaintext static code. Static mode retains the existing redacted code response for compatibility.

### Issue the current dynamic credential

```http
GET /api/v1/check-in-sessions/{sessionId}/dynamic-code
```

Requires `checkin:manage`. The service rechecks the MySQL session and activity state before issuing a credential.

```json
{
  "data": {
    "sessionId": "...",
    "activityId": "...",
    "windowEpochSecond": 1792070100,
    "expiresAt": "2026-10-15T13:15:45Z",
    "token": "..."
  }
}
```

### Dynamic self-check-in

```http
POST /api/v1/check-in-sessions/{sessionId}/dynamic-check-ins
```

```json
{
  "windowEpochSecond": 1792070100,
  "token": "..."
}
```

The response uses the existing `CheckinResult` contract. Static self-check-in remains at `/check-in-sessions/{sessionId}/check-ins`.

## Data Flow and Transaction Boundary

Issuance:

1. Load the session and activity from MySQL and require `checkin:manage`.
2. Reject a non-dynamic, closed, expired, or final session.
3. Calculate the current window and random token.
4. Atomically keep or read the window's Redis value with its TTL.
5. Return the token and absolute expiry without logging the credential.

Dynamic check-in deliberately separates Redis I/O from the MySQL transaction:

1. A non-transactional admission service validates the request shape and compares the submitted token to the Redis value using constant-time byte comparison.
2. It returns an internal immutable proof containing only the session, window, and absolute expiry.
3. The existing transactional check-in service locks activity, session, membership, and registration-cycle rows in the established order.
4. After any lock wait, it resamples the clock and rejects an expired proof or a session that is no longer open.
5. It inserts the attendance record and updates the cycle using existing unique constraints, audit logic, and duplicate-check-in mapping.

This avoids a Redis network call while holding MySQL row locks. A credential valid before a long lock wait cannot be used after its proof expiry.

## Frontend Flow

The management page requests the current dynamic credential immediately and shortly after each window changes. It renders a QR code locally and shows a countdown based on `expiresAt`; it does not continuously poll faster than the rotation interval.

The QR target is the existing member check-in page. Public identifiers may be in the path or query, but the credential is placed in the URL fragment:

```text
/activities/<activityId>/check-in?sessionId=<sessionId>#window=<window>&token=<token>
```

Fragments are not sent in HTTP requests and therefore are not written to ordinary Nginx access logs. The check-in page reads the fragment, immediately removes it with `history.replaceState`, and submits the credential only after the authenticated member confirms. No browser-camera package is required because the phone's native camera opens the encoded URL.

The UI handles loading, countdown, rotation, expired QR, already checked in, permission failure, and Redis-unavailable states. Visual restyling is outside this slice; the implementation follows the current components and can be redesigned later.

## Failure Behavior

- Redis unavailable during issue or verification: return `503 DYNAMIC_CHECKIN_UNAVAILABLE`; do not open a database transaction for dynamic self-check-in.
- Missing or expired key, wrong token, unsupported window, or malformed request: return `422 INVALID_DYNAMIC_CHECKIN_CODE` without revealing which field failed.
- Session closed or expired after credential verification: return the existing session-not-open conflict from the MySQL transaction.
- Duplicate attendance: return the existing `ALREADY_CHECKED_IN` conflict; Redis does not decide uniqueness.
- Session close: commit the MySQL state first, then best-effort delete the current and previous window keys. Failure is logged without token data and bounded by TTL.
- Redis recovers: new windows resume normally; no reconstruction or database reconciliation is needed.

## Configuration

```yaml
volunteerflow:
  checkin:
    dynamic-code:
      window: ${DYNAMIC_CHECKIN_WINDOW:30s}
      ttl: ${DYNAMIC_CHECKIN_TTL:45s}
      token-bytes: ${DYNAMIC_CHECKIN_TOKEN_BYTES:24}
```

The existing Redis command timeout remains authoritative. Credentials and Redis connection secrets must never appear in logs or actuator output.

## Verification

Automated tests must prove:

- dynamic session creation stores no static-code digest and static mode remains compatible;
- RBAC is enforced before manager credential issuance;
- the same window converges on one token and the next window rotates it;
- configured TTL and expiry are correct, including the previous-window grace boundary;
- malformed, wrong, expired, and cross-session credentials are rejected uniformly;
- Redis issue/verify failures return 503 while close and manual check-in remain usable;
- Redis validation completes before the MySQL transaction, and proof expiry is rechecked after locks;
- closing a session schedules best-effort key cleanup only after commit;
- concurrent or repeated dynamic check-ins still produce one attendance record;
- QR secrets use the URL fragment, are cleared from browser history, and are redacted from logs and string representations;
- backend and frontend full suites remain green without requiring a live Redis server.

Live Redis TTL behavior and end-to-end phone scanning must be verified separately against the user's virtual-machine Redis environment before claiming runtime success.
