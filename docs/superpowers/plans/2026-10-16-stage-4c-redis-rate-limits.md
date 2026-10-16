# Stage 4C Redis Rate Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add shared Redis rate limits to login, activity registration, promotion responses, and self check-in while preserving MySQL correctness and a bounded login defense during Redis outages.

**Architecture:** A fixed-window Redis adapter executes one Lua script for atomic increment and expiration. An explicit `RateLimitGuard` hashes subjects with HMAC, applies per-action policies at controller command boundaries, fails open for business commands, and delegates login to a bounded local fallback when Redis is unavailable.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Data Redis/Lettuce, Spring MVC, JUnit 5, Mockito, AssertJ, Vue/Vitest deployment contract tests

**Spec:** `docs/superpowers/specs/2026-10-16-stage-4c-redis-rate-limits-design.md`

## Global Constraints

- Protect only login, activity registration, promotion-offer response, static self check-in, and dynamic self check-in.
- Use Redis fixed windows with an atomic Lua increment-plus-expiry operation; do not add Redisson, Bucket4j, sorted sets, or token buckets.
- Default policies are login IP `20/1m`, login username `5/5m`, registration `3/10s`, promotion response `5/1m`, static check-in `5/1m`, and dynamic check-in `10/1m`.
- Redis keys must use `volunteerflow:<environment>:rate-limit:<action>:<subject-hmac>:<window-start>` and must not contain raw usernames, addresses, IDs, codes, or tokens.
- HMAC-SHA-256 uses a dedicated `RATE_LIMIT_KEY_SECRET` of at least 32 bytes with purpose separation.
- Redis failures fail open for registration, promotion, and check-in; login evaluates both policies with a capacity-bounded local fallback.
- Rejection is HTTP `429`, code `RATE_LIMITED`, a request ID, and `Retry-After` of at least one second.
- Application code reads the effective servlet `remoteAddr` and never parses arbitrary forwarded headers.
- Preserve existing MySQL transactions, locks, unique constraints, RBAC, response envelopes, and the no-physical-foreign-key schema rule.
- Keep Stage 4D distributed coordination and k6 work out of this plan.

## Review Focus

- Malformed or unexpected Redis script results must enter the documented unavailable path instead of causing a `500`; Task 2 adds the adapter test.
- A request exactly at a fixed-window boundary must use the new key and always produce `Retry-After >= 1`; Tasks 2 and 4 add clock and HTTP tests.
- Whitespace/case variants of a login username must share one pseudonymous subject without changing authentication lookup behavior; Task 3 adds the guard test.
- Concurrent local-fallback attempts at the limit and during capacity eviction must not overshoot or grow the map beyond its bound; Task 3 adds concurrent and capacity tests.
- A client-supplied forwarded chain must not choose the limiter subject; Task 4 tests servlet `remoteAddr`, and Task 5 tests that Nginx overwrites forwarded headers.

---

### Task 1: Typed Policies and Pseudonymous Subjects

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitDecision.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitSubjectHasher.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/RateLimitPropertiesTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/RateLimitSubjectHasherTest.java`
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java`

**Interfaces:**
- Consumes: existing Spring configuration binding and `RedisInfrastructureConfig` registration pattern.
- Produces: `RateLimitProperties(boolean enabled, String keySecret, LoginPolicies login, Policy registration, Policy promotionResponse, Policy staticCheckin, Policy dynamicCheckin, LocalLogin localLogin)`; nested `Policy(int limit, Duration window)`, `LoginPolicies(Policy ip, Policy username)`, and `LocalLogin(int maxEntries, Duration cleanupInterval)`; `RateLimitDecision(boolean allowed, long retryAfterSeconds)`; `RateLimitSubjectHasher(String keySecret)` and `String hash(String purpose, String rawSubject)`.

- [ ] **Step 1: Write failing configuration tests**

Add tests named `acceptsDocumentedPolicies`, `rejectsNonPositiveLimit`, `rejectsWindowShorterThanOneSecond`, `rejectsShortKeySecretWhenEnabled`, `allowsBlankKeySecretWhenDisabled`, and `rejectsFallbackCapacityBelowLoginLimits`. Construct the exact policy values from the Global Constraints and assert invalid records fail immediately.

- [ ] **Step 2: Run configuration tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RateLimitPropertiesTest test`

Expected: compilation fails because `RateLimitProperties` does not exist.

- [ ] **Step 3: Implement the typed properties and register them**

Implement the exact records in the Interfaces block, validate constructor invariants, and add `RateLimitProperties.class` to `RedisInfrastructureConfig`'s `@EnableConfigurationProperties` list. Keep environment-variable YAML wiring for Task 5.

- [ ] **Step 4: Run configuration tests and observe GREEN**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RateLimitPropertiesTest test`

Expected: all `RateLimitPropertiesTest` tests pass.

- [ ] **Step 5: Write failing HMAC tests**

Add tests named `hashIsDeterministicUrlSafeAndDoesNotExposeSubject`, `purposeSeparatesTheSameSubject`, and `rejectsBlankPurposeOrSubject`. Calculate the expected HMAC independently with JCA in the test rather than copying production output.

- [ ] **Step 6: Run HMAC tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RateLimitSubjectHasherTest test`

Expected: compilation fails because `RateLimitSubjectHasher` does not exist.

- [ ] **Step 7: Implement the subject hasher and decision record**

Use HMAC-SHA-256 with the domain-separated input `volunteerflow-rate-limit-v1\0<purpose>\0<subject>` and unpadded Base64URL output. Create a new `Mac` per call because JCA `Mac` is mutable and not thread-safe.

- [ ] **Step 8: Run Task 1 tests**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RateLimitPropertiesTest,RateLimitSubjectHasherTest test`

Expected: all Task 1 tests pass.

- [ ] **Step 9: Commit Task 1**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/ratelimit backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java backend/src/test/java/com/volunteerflow/infrastructure/ratelimit
git commit -m "feat: define rate limit policies and subjects"
```

### Task 2: Atomic Redis Fixed-Window Store

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitStore.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitStoreUnavailableException.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RedisFixedWindowRateLimiter.java`
- Create: `backend/src/main/resources/redis/rate-limit-fixed-window.lua`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/RedisFixedWindowRateLimiterTest.java`

**Interfaces:**
- Consumes: Task 1 `RateLimitProperties.Policy`, `RateLimitDecision`, and the existing `RedisKeyFactory.key(String module, String businessKey)`.
- Produces: `RateLimitStore.evaluate(String action, String subjectHash, RateLimitProperties.Policy policy)` returning `RateLimitDecision` or throwing `RateLimitStoreUnavailableException`; `RedisFixedWindowRateLimiter(StringRedisTemplate template, RedisKeyFactory keys, Clock clock)`.

- [ ] **Step 1: Write failing fixed-window adapter tests**

Add tests named `usesEnvironmentAwareWindowKeyAndAllowsExactlyTheLimit`, `switchesKeyAtExactWindowBoundary`, `retryAfterIsNeverBelowOneSecond`, `malformedScriptReplyIsUnavailable`, `redisConnectionFailureIsUnavailable`, and `doesNotLeakActionSubjectOrKeyThroughExceptionText`. Mock the template/script execution and use a mutable clock; assert the key module is `rate-limit` and the business key is `<action>:<subjectHash>:<windowStartEpochMillis>`.

- [ ] **Step 2: Run adapter tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RedisFixedWindowRateLimiterTest test`

Expected: compilation fails because the store and adapter do not exist.

- [ ] **Step 3: Add the Lua resource**

The script accepts one key and the calculated TTL milliseconds. It atomically runs `INCR`, sets `PEXPIRE` only when the result is `1`, reads `PTTL`, and returns exactly `[count, ttlMillis]`.

- [ ] **Step 4: Implement the Redis adapter**

Calculate `windowStart` from the injected `Clock`, set script TTL to the remaining window plus 1000 ms, map count/TTL to the Task 1 decision, and translate null, wrong-length, nonnumeric, nonpositive-TTL, Redis connection, timeout, serialization, and script exceptions to `RateLimitStoreUnavailableException` without including identifiers.

- [ ] **Step 5: Run Task 2 tests**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RedisFixedWindowRateLimiterTest test`

Expected: all Task 2 tests pass.

- [ ] **Step 6: Commit Task 2**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/ratelimit backend/src/main/resources/redis/rate-limit-fixed-window.lua backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/RedisFixedWindowRateLimiterTest.java
git commit -m "feat: add atomic redis rate limiter"
```

### Task 3: Hybrid Failure Policy and Bounded Login Fallback

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/LocalLoginRateLimiter.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitExceededException.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/ratelimit/RateLimitGuard.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/LocalLoginRateLimiterTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/ratelimit/RateLimitGuardTest.java`

**Interfaces:**
- Consumes: Task 1 properties/hasher/decision and Task 2 `RateLimitStore` unavailable boundary.
- Produces: `LocalLoginRateLimiter(Clock clock, RateLimitProperties.LocalLogin settings)` with `RateLimitDecision evaluate(String action, String subjectHash, RateLimitProperties.Policy policy)`; `RateLimitExceededException(long retryAfterSeconds)`; `RateLimitGuard.checkLogin(String clientAddress, String username)`, `checkRegistration(long userId, long activityId)`, `checkPromotionResponse(long userId, long offerId)`, `checkStaticCheckin(long userId, long sessionId)`, and `checkDynamicCheckin(long userId, long sessionId)`.

- [ ] **Step 1: Write failing local fallback tests**

Add tests named `allowsExactlyTheLimitThenRejects`, `resetsAtExactWindowBoundary`, `concurrentAttemptsDoNotOvershootLimit`, `expiredEntriesAreRemovedDuringCleanup`, and `capacityNeverExceedsConfiguredMaximumUnderConcurrentSubjects`. Assert rejected decisions return a retry of at least one second without sleeping.

- [ ] **Step 2: Run fallback tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=LocalLoginRateLimiterTest test`

Expected: compilation fails because `LocalLoginRateLimiter` does not exist.

- [ ] **Step 3: Implement the bounded local limiter**

Use one synchronized access path over a bounded insertion-ordered map. On configured cleanup intervals remove expired entries; before inserting at capacity remove expired entries and then evict the oldest remaining entry. Reuse the same fixed-window calculation and minimum-one-second retry rule as Redis.

- [ ] **Step 4: Run fallback tests and observe GREEN**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=LocalLoginRateLimiterTest test`

Expected: all local fallback tests pass.

- [ ] **Step 5: Write failing guard tests**

Add tests named `loginChecksIpAndCanonicalUsernameInRedis`, `loginWhitespaceAndCaseVariantsShareSubject`, `loginFallsBackForBothPoliciesOnAnyStoreFailure`, `loginRejectsWhenEitherDecisionRejects`, `businessActionsUseExpectedCompositeSubjects`, `businessActionsFailOpenOnlyForStoreUnavailable`, `disabledConfigurationBypassesAllStores`, and `warningTextDoesNotContainRawSubjects`. Capture logs for the final assertion.

- [ ] **Step 6: Run guard tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RateLimitGuardTest test`

Expected: compilation fails because `RateLimitGuard` and `RateLimitExceededException` do not exist.

- [ ] **Step 7: Implement the guard and exceeded exception**

Normalize login usernames with `trim().toLowerCase(Locale.ROOT)`. Hash each purpose-separated composite subject before store access. If either Redis login check is unavailable, evaluate both IP and username locally. Catch only `RateLimitStoreUnavailableException` for fail-open business behavior; programming exceptions must still surface.

Use these exact action/purpose names and raw subject shapes: `login-ip` with `<clientAddress>`, `login-username` with `<normalizedUsername>`, `registration` with `<userId>:<activityId>`, `promotion-response` with `<userId>:<offerId>`, `static-checkin` with `<userId>:<sessionId>`, and `dynamic-checkin` with `<userId>:<sessionId>`.

- [ ] **Step 8: Run Task 3 tests**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=LocalLoginRateLimiterTest,RateLimitGuardTest test`

Expected: all Task 3 tests pass.

- [ ] **Step 9: Commit Task 3**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/ratelimit backend/src/test/java/com/volunteerflow/infrastructure/ratelimit
git commit -m "feat: add resilient rate limit guard"
```

### Task 4: HTTP 429 Contract and Protected Command Boundaries

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/volunteerflow/auth/AuthController.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationController.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinController.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandlerRateLimitTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/auth/AuthControllerTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/RegistrationControllerTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java`

**Interfaces:**
- Consumes: Task 3 `RateLimitGuard` methods and `RateLimitExceededException.retryAfterSeconds()`.
- Produces: a `GlobalExceptionHandler` exception mapping to HTTP `429` with `Retry-After`; guard calls at the five protected controller methods before their domain services.

- [ ] **Step 1: Write the failing 429 handler tests**

Add tests named `rateLimitResponseContainsProblemCodeRequestIdAndRetryAfter` and `retryAfterIsClampedToAtLeastOneWithoutSubjectDetails`. Assert `application/problem+json`, status `429`, code `RATE_LIMITED`, the request ID, and absence of username/IP/IDs.

- [ ] **Step 2: Run handler tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=GlobalExceptionHandlerRateLimitTest test`

Expected: status or header assertions fail because no dedicated handler exists.

- [ ] **Step 3: Implement the global 429 mapping**

Add one dedicated exception handler that sets `Retry-After` to the clamped whole-second value and builds the same `ProblemDetail` shape as existing business errors.

- [ ] **Step 4: Run handler tests and observe GREEN**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=GlobalExceptionHandlerRateLimitTest test`

Expected: both handler tests pass.

- [ ] **Step 5: Write failing controller-boundary tests**

Update controller construction/mocks and add tests named `loginUsesServletRemoteAddressAndUsernameBeforeService`, `loginIgnoresClientSuppliedForwardedChain`, `registrationChecksUserAndActivityBeforeSubmission`, `promotionResponseChecksUserAndOfferBeforeService`, `staticCheckinChecksUserAndSessionBeforeService`, `dynamicCheckinChecksUserAndSessionBeforeService`, and one rejection test per controller proving the domain service has no interaction after the guard throws.

- [ ] **Step 6: Run controller tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=AuthControllerTest,RegistrationControllerTest,CheckinControllerTest test`

Expected: verification failures because controllers do not invoke `RateLimitGuard`.

- [ ] **Step 7: Integrate the guard into controllers**

Inject `RateLimitGuard`; add `HttpServletRequest` only to login; invoke the exact Task 3 method after MVC validation/authentication and before the existing service call. Do not guard register, refresh, cancellation, review, manual check-in, session management, or queries.

- [ ] **Step 8: Run Task 4 tests**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=GlobalExceptionHandlerRateLimitTest,AuthControllerTest,RegistrationControllerTest,CheckinControllerTest test`

Expected: all Task 4 tests pass and existing response assertions remain unchanged.

- [ ] **Step 9: Commit Task 4**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandler.java backend/src/main/java/com/volunteerflow/auth/AuthController.java backend/src/main/java/com/volunteerflow/registration/RegistrationController.java backend/src/main/java/com/volunteerflow/checkin/CheckinController.java backend/src/test/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandlerRateLimitTest.java backend/src/test/java/com/volunteerflow/auth/AuthControllerTest.java backend/src/test/java/com/volunteerflow/registration/RegistrationControllerTest.java backend/src/test/java/com/volunteerflow/checkin/CheckinControllerTest.java
git commit -m "feat: enforce command rate limits"
```

### Task 5: Runtime Wiring, Proxy Safety, and Operations Contract

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Modify: `backend/.env.example`
- Modify: `deploy/.env.example`
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/nginx/nginx.conf`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/redis/RedisFoundationTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/DeploymentComposeContractTest.java`
- Modify: `frontend/src/test/FrontendContainerContract.test.ts`
- Modify: `docs/operations/stage-4-redis.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-3 constructors and configuration records.
- Produces: Spring beans for `Clock`, hasher, Redis store, local fallback, and guard; environment overrides for every policy; trusted Nginx-to-backend client-address behavior; accurate Stage 4C operational documentation.

- [ ] **Step 1: Write failing Spring and deployment contract tests**

Extend `RedisFoundationTest` to assert all rate-limit beans and disabled-mode startup. Extend `DeploymentComposeContractTest` to assert the dedicated key secret and policy variables reach the backend without changing DB/Redis host defaults. Extend `FrontendContainerContract.test.ts` to require `X-Forwarded-For $remote_addr` and reject `$proxy_add_x_forwarded_for`.

- [ ] **Step 2: Run contract tests and observe RED**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RedisFoundationTest,DeploymentComposeContractTest test`

Run: `npm test -- --run src/test/FrontendContainerContract.test.ts`

Expected: bean/config/header assertions fail because runtime wiring is absent and Nginx still appends the incoming chain.

- [ ] **Step 3: Add runtime beans and application properties**

Reuse the UTC `Clock` already supplied by `AuthInfrastructureConfig`. Register `rateLimitSubjectHasher(RateLimitProperties)`, `rateLimitStore(StringRedisTemplate, RedisKeyFactory, Clock)`, `localLoginRateLimiter(Clock, RateLimitProperties)`, and `rateLimitGuard(RateLimitStore, LocalLoginRateLimiter, RateLimitSubjectHasher, RateLimitProperties)` beans. Add the exact configuration shape and environment names from the spec, set `server.forward-headers-strategy: native`, and add a valid test-only key secret to `application-test.yml`.

- [ ] **Step 4: Update environment and Compose contracts**

Add `RATE_LIMIT_KEY_SECRET`, enable flag, all policy overrides, and local fallback settings to both examples and the backend Compose environment. Preserve the user's existing MySQL/Redis addresses and ports; examples use placeholders rather than replacing real local configuration.

- [ ] **Step 5: Harden Nginx forwarded headers**

Replace `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` with `proxy_set_header X-Forwarded-For $remote_addr;`. Keep backend unexposed in Compose and preserve the existing exact `/api` and `/actuator` proxy locations.

- [ ] **Step 6: Run contract tests and observe GREEN**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=RedisFoundationTest,DeploymentComposeContractTest test`

Run: `npm test -- --run src/test/FrontendContainerContract.test.ts`

Expected: all Stage 4C wiring and proxy contract tests pass.

- [ ] **Step 7: Update README and the Stage 4 Redis runbook**

Document policies, `429`/`Retry-After`, HMAC secret generation/rotation, Redis fail-open versus login fallback behavior, safe proxy assumptions, verification commands, and the boundary that live VM Redis, multi-instance enforcement, Nginx client IP, and capacity are not yet proven. Mark Stage 4C source/automated verification separately from Stage 4D k6 evidence.

- [ ] **Step 8: Run full regression verification**

Run: `backend\mvnw.cmd -f backend\pom.xml test`

Expected: all backend tests pass with zero failures and errors.

Run: `npm test -- --run && npm run build`

Expected: all frontend tests pass and Vite production build succeeds.

- [ ] **Step 9: Search for credential and subject leakage**

Run: `rg -n "RATE_LIMIT_KEY_SECRET|rawSubject|username|clientAddress|X-Forwarded-For|dynamicCode|check-in code" backend/src/main backend/src/test docs/operations/stage-4-redis.md README.md`

Expected: secrets appear only as environment/property names; logs and exception messages contain no raw subject, static code, or dynamic token values. Review every match rather than treating an empty/nonempty result as automatic success.

- [ ] **Step 10: Commit Task 5**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/redis/RedisInfrastructureConfig.java backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml backend/.env.example deploy/.env.example deploy/docker-compose.yml deploy/nginx/nginx.conf backend/src/test/java/com/volunteerflow/infrastructure/redis/RedisFoundationTest.java backend/src/test/java/com/volunteerflow/DeploymentComposeContractTest.java frontend/src/test/FrontendContainerContract.test.ts docs/operations/stage-4-redis.md README.md
git commit -m "docs: complete stage 4c rate limit operations"
```

## Final Verification

- Run the full backend suite, frontend suite, and production build again on the final tree.
- Review the complete diff against the spec, especially every Review Focus item and every `RateLimitStoreUnavailableException` catch boundary.
- Confirm `git status --short` contains no task-owned uncommitted files.
- Treat VM Redis, multi-instance, Nginx runtime, and performance results as pending until Stage 4D executes them in a deployed environment.
