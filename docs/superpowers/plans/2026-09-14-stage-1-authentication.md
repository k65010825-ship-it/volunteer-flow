# Stage 1 Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a working Java 17 registration, login, JWT, rotating refresh-session, and one-time platform-admin bootstrap flow before organization and activity work.

**Architecture:** Keep account persistence in `auth` via MyBatis-Plus and MySQL; use Spring Security Resource Server to verify short-lived signed JWTs. Persist only SHA-256 refresh-token hashes in `refresh_session`, send raw refresh tokens exclusively as HttpOnly cookies, and load current user status and role from MySQL for protected requests. Bootstrap is a startup-only path using explicitly supplied environment variables and never a public API.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Security 6, MyBatis-Plus 3.5.17, MySQL, Flyway, JUnit 5, MockMvc.

**Spec:** `docs/specs/2026-09-14-volunteerflow-revised-design.md`, sections 3, 4.1, 10, 12; user decision: bootstrap option A.

## Global Constraints

- Preserve the existing V1–V8 migrations and do not add physical foreign keys.
- Public registration always creates `platform_role=USER`; no HTTP route may create `PLATFORM_ADMIN`.
- Startup creates an admin only when all `BOOTSTRAP_ADMIN_*` values are present and no platform admin exists; partial configuration fails startup; repeated startup is a no-op.
- Passwords are BCrypt hashes. JWT signing key and bootstrap password come only from untracked local config or environment variables.
- Access JWT lifetime is 30 minutes; refresh-session lifetime is 30 days, device-specific and rotated on every refresh.
- Success returns `ApiResponse`; errors use the existing `ProblemDetail` contract.
- Redis is not an authentication correctness dependency in this slice.

---

### Task 1: Account persistence and administrator bootstrap

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/auth/AppUser.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AppUserMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AdminBootstrapProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AdminBootstrapInitializer.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AuthInfrastructureConfig.java`
- Test: `backend/src/test/java/com/volunteerflow/auth/AdminBootstrapInitializerTest.java`
- Modify: `backend/src/main/resources/application.yml`, `backend/.env.example`, `backend/README.md`

**Interfaces:** `AdminBootstrapInitializer.initialize()` creates the first `AppUser` using `AppUserMapper`; later tasks reuse `AppUserMapper` and the configured `PasswordEncoder`.

- [ ] **Step 1: Write the failing unit tests.** Test complete configuration inserts one `PLATFORM_ADMIN` with encoded password; existing admin inserts nothing; absent configuration performs no DB call; partial configuration throws without writing. Assert through the mapper argument and BCrypt `matches`, not by comparing raw hash strings.
- [ ] **Step 2: Verify red.** Run `backend/mvnw.cmd -f backend/pom.xml -Dtest=AdminBootstrapInitializerTest test -ntp`; expect compile failure because the initializer is absent.
- [ ] **Step 3: Implement minimal code.** Map `app_user` with `@TableName`, `@TableId(type=IdType.ASSIGN_ID)`, ordinary getters/setters; declare `@Mapper interface AppUserMapper extends BaseMapper<AppUser>`. Bind five bootstrap fields with `@ConfigurationProperties(prefix="volunteerflow.bootstrap-admin")`; refuse partial input. In `initialize()`, call `selectCount` for `PLATFORM_ADMIN` before insert and create a BCrypt-hashed active admin. Register `ApplicationRunner` only outside the `test` profile.
- [ ] **Step 4: Verify green.** Run the targeted test and then `backend/mvnw.cmd -f backend/pom.xml test -ntp`; expect zero failures.
- [ ] **Step 5: Commit.** `git add backend docs/superpowers/plans/2026-09-14-stage-1-authentication.md`; `git commit -m "feat: add one-time platform admin bootstrap"`.

### Task 2: Registration and login

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/auth/AuthController.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AuthService.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/RegisterRequest.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/LoginRequest.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/AuthTokens.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/RefreshSession.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/RefreshSessionMapper.java`
- Test: `backend/src/test/java/com/volunteerflow/auth/AuthServiceTest.java`
- Test: `backend/src/test/java/com/volunteerflow/auth/AuthControllerTest.java`

**Interfaces:** `POST /api/v1/auth/register` accepts username, password, realName, studentNumber, contact and creates a `USER`; `POST /api/v1/auth/login` accepts username/password. Both return an access token and set a secure-random refresh cookie. `AuthService.register(RegisterRequest)` and `AuthService.login(LoginRequest)` return `AuthTokens`.

- [ ] **Step 1: Write failing tests.** Registration rejects duplicate username/student number and never accepts a client platform role; login returns indistinguishable failures for unknown user and wrong password; disabled users cannot log in; valid login creates a refresh session storing only SHA-256 token hash.
- [ ] **Step 2: Verify red.** Run `backend/mvnw.cmd -f backend/pom.xml -Dtest=AuthServiceTest,AuthControllerTest test -ntp`; expect missing types or failing assertions.
- [ ] **Step 3: Implement minimal code.** Use `@Valid` request records; BCrypt verification; `SecureRandom` for 32-byte refresh tokens; MyBatis-Plus mapper calls inside `@Transactional`; cookie `HttpOnly`, `SameSite=Lax`, `Path=/api/v1/auth`, and `Secure` according to HTTPS deployment setting.
- [ ] **Step 4: Verify green.** Run targeted tests and full Maven `test`; expect zero failures.
- [ ] **Step 5: Commit.** `git add backend`; `git commit -m "feat: add registration and login"`.

### Task 3: JWT validation and rotating refresh sessions

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/auth/JwtTokenService.java`
- Create: `backend/src/main/java/com/volunteerflow/auth/CurrentUser.java`
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/security/SecurityConfig.java`
- Modify: `backend/src/main/java/com/volunteerflow/auth/AuthController.java`
- Modify: `backend/src/main/java/com/volunteerflow/auth/AuthService.java`
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/.env.example`
- Test: `backend/src/test/java/com/volunteerflow/auth/RefreshSessionTest.java`
- Test: `backend/src/test/java/com/volunteerflow/infrastructure/security/SecurityConfigTest.java`

**Interfaces:** `POST /api/v1/auth/refresh` rotates the cookie and returns a new JWT; `POST /api/v1/auth/logout` revokes only the cookie's session; `GET /api/v1/auth/me` reads active user data. Protected requests require a signed, unexpired JWT and an active user in MySQL.

- [ ] **Step 1: Write failing tests.** Verify tampered/expired JWT is 401; refresh replay fails after first rotation; logout revokes only one device session; disabled user is 401 even with an otherwise valid JWT.
- [ ] **Step 2: Verify red.** Run targeted Maven tests; expect missing routes or failed HTTP assertions.
- [ ] **Step 3: Implement minimal code.** Add Spring Security OAuth2 Resource Server/Jose dependencies. Use a configured strong HMAC key, issuer/audience validation, 30-minute JWTs, current user lookup on each authenticated request, and atomic update of the refresh-session token hash under a DB row lock.
- [ ] **Step 4: Verify green.** Run targeted tests, full Maven tests, and a package build; expect zero failures.
- [ ] **Step 5: Commit.** `git add backend`; `git commit -m "feat: add JWT and rotating refresh sessions"`.

### Task 4: Real-database acceptance and auth documentation

**Files:**
- Modify: `backend/README.md`
- Create: `backend/src/test/java/com/volunteerflow/auth/AuthMySqlIntegrationTest.java`

**Interfaces:** A documented sequence registers an ordinary user, logs in, refreshes, logs out, and verifies a single bootstrap admin. Test DB is isolated from the user's populated development database.

- [ ] **Step 1: Write a failing integration test.** Use a disposable MySQL instance/database and assert user and refresh-session rows; never truncate the user's configured `volunteer_flow` database.
- [ ] **Step 2: Verify red.** Run the integration test; expect a behavior failure, not a network error.
- [ ] **Step 3: Complete the smallest missing code and documentation.** Record required environment variables, cookie/HTTPS behavior, IDE run steps, and manual API calls without any secret values.
- [ ] **Step 4: Verify green.** Run the integration test, full tests, `package`, and `git diff --check`; expect zero failures.
- [ ] **Step 5: Commit.** `git add backend`; `git commit -m "test: verify authentication against MySQL"`.

The next independently testable plans cover organization/invite/RBAC, then activity/positions, then the responsive Vue 3 client. This plan does not claim those parts are complete.
