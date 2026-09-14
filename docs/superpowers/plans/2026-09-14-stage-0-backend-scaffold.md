# Stage 0 Backend Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a runnable Java 17 Spring Boot backend scaffold with the approved module boundaries, common API infrastructure, MyBatis-Plus/Flyway/MySQL configuration, Actuator health endpoint, and verified tests.

**Architecture:** The backend is one Maven-based Spring Boot modular monolith under `backend/`. Feature packages define ownership boundaries while shared HTTP, persistence, and security configuration stays under `infrastructure`. MySQL remains the only runtime data dependency; Redis and Kafka are intentionally absent from this scaffold.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Maven 3.9.x, Spring Security 6, MyBatis-Plus 3.5.17, Flyway, MySQL 8, JUnit 5, MockMvc

**Spec:** `docs/specs/2026-09-14-volunteerflow-revised-design.md`

## Global Constraints

- Use Java 17 and Spring Boot 3.x.
- Keep one Spring Boot application; do not create Maven submodules.
- Use package root `com.volunteerflow`.
- Use MyBatis-Plus for routine persistence and reserve custom Mapper SQL for locking and complex queries.
- Flyway is the only schema migration mechanism.
- Do not add Redis or Kafka dependencies before their planned stages.
- Use constructor injection; never use field injection.
- Externalize credentials through environment variables.
- Do not create empty controllers, services, or mappers for unimplemented business features.
- All production behavior added by this plan must have a test that was observed failing first.

---

### Task 1: Maven Spring Boot application bootstrap

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java`
- Create: `backend/src/test/java/com/volunteerflow/VolunteerFlowApplicationTests.java`
- Create: `backend/src/test/resources/application-test.yml`
- Generate: `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/*`

**Interfaces:**
- Consumes: Java 17 and Maven available on the workstation.
- Produces: `com.volunteerflow.VolunteerFlowApplication` and a Maven project runnable with `mvn spring-boot:run`.

- [ ] **Step 1: Create the Maven descriptor**

Define Spring Boot parent `3.5.16`, Java `17`, and dependencies for Web, Validation, Security, Actuator, MyBatis-Plus `3.5.17`, Flyway, Flyway MySQL support, MySQL Connector/J, Spring Boot Test, and Spring Security Test.

- [ ] **Step 2: Write the context test before the application class**

```java
@ActiveProfiles("test")
@SpringBootTest
class VolunteerFlowApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

The test profile excludes datasource, Flyway, and MyBatis-Plus auto-configuration so the framework test does not require a running database.

- [ ] **Step 3: Run the test and verify RED**

Run: `mvn -f backend/pom.xml test -Dtest=VolunteerFlowApplicationTests`

Expected: compilation fails because `VolunteerFlowApplication` does not exist.

- [ ] **Step 4: Add the minimal application class**

```java
@SpringBootApplication
public class VolunteerFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(VolunteerFlowApplication.class, args);
    }
}
```

- [ ] **Step 5: Run the test and verify GREEN**

Run: `mvn -f backend/pom.xml test -Dtest=VolunteerFlowApplicationTests`

Expected: one test passes with no failures.

- [ ] **Step 6: Generate Maven Wrapper**

Run from `backend/`: `mvn wrapper:wrapper -Dmaven=3.9.12`

Expected: `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/` are generated.

### Task 2: Establish feature package boundaries

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/auth/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/organization/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/rbac/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/checkin/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/audit/package-info.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/package-info.java`

**Interfaces:**
- Consumes: package root `com.volunteerflow`.
- Produces: documented ownership boundaries for all approved backend modules.

- [ ] **Step 1: Add package documentation**

Each `package-info.java` states the module's responsibility. No empty services, controllers, entities, or mappers are created because they would imply behavior that has not been designed test-first.

- [ ] **Step 2: Compile the application**

Run: `mvn -f backend/pom.xml compile`

Expected: build succeeds.

### Task 3: Common API response and exception handling

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/web/ApiResponse.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/web/BusinessException.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Spring MVC validation and `ProblemDetail`.
- Produces: `ApiResponse<T>.of(T data)`, `BusinessException(HttpStatus status, String code, String message)`, and consistent error bodies containing `code` and `requestId`.

- [ ] **Step 1: Write failing MVC tests**

Create a test-only controller with endpoints that throw `BusinessException` and validate an empty request. Assert that business errors use the configured HTTP status and that validation failures return status 400. Both responses must include `code` and `requestId`.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -f backend/pom.xml test -Dtest=GlobalExceptionHandlerTest`

Expected: test compilation fails because the production types do not exist.

- [ ] **Step 3: Implement the minimal response and handler types**

`ApiResponse<T>` is an immutable Java record. `GlobalExceptionHandler` returns Spring `ProblemDetail`, adds a stable business `code`, reads `requestId` from the current request, and never returns a stack trace.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -f backend/pom.xml test -Dtest=GlobalExceptionHandlerTest`

Expected: all handler tests pass.

### Task 4: Request correlation and security baseline

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/web/RequestIdFilter.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/security/SecurityConfig.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/web/RequestIdFilterTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/security/SecurityConfigTest.java`

**Interfaces:**
- Consumes: servlet requests and Spring Security filter chain.
- Produces: `X-Request-Id` response headers and a security policy that permits `/actuator/health` while protecting other application endpoints.

- [ ] **Step 1: Write the request ID filter test**

Assert that the filter accepts a valid incoming `X-Request-Id`, generates one when absent, exposes it as a request attribute, returns it as a response header, and clears MDC after the request.

- [ ] **Step 2: Run the filter test and verify RED**

Run: `mvn -f backend/pom.xml test -Dtest=RequestIdFilterTest`

Expected: compilation fails because `RequestIdFilter` does not exist.

- [ ] **Step 3: Implement the filter and verify GREEN**

Implement a `OncePerRequestFilter` with a conservative request ID pattern and UUID fallback. Run the same test and expect all assertions to pass.

- [ ] **Step 4: Write a failing security integration test**

Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with the test profile. Assert that `GET /actuator/health` returns 200 without authentication and an unknown `/api/v1/private-probe` request does not return a successful status.

- [ ] **Step 5: Implement `SecurityConfig` and verify GREEN**

Use a `SecurityFilterChain`, stateless sessions, disabled form login and HTTP Basic, and permit only health plus explicit future authentication endpoints. Run `SecurityConfigTest` and expect all assertions to pass.

### Task 5: Persistence and runtime configuration

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/persistence/MybatisPlusConfig.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__create_schema_history_marker.sql`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/MybatisPlusConfigTest.java`

**Interfaces:**
- Consumes: MyBatis-Plus and environment variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`.
- Produces: a pagination interceptor configured for MySQL and runtime configuration ready for the full schema migration task.

- [ ] **Step 1: Write a failing configuration test**

Assert that `MybatisPlusConfig` exposes a `MybatisPlusInterceptor` containing one `PaginationInnerInterceptor` configured for `DbType.MYSQL`.

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -f backend/pom.xml test -Dtest=MybatisPlusConfigTest`

Expected: compilation fails because `MybatisPlusConfig` does not exist.

- [ ] **Step 3: Implement persistence configuration**

Configure MySQL datasource values from environment variables, enable Flyway with `classpath:db/migration`, disable Hibernate/JPA assumptions, and expose only Actuator health and info endpoints.

- [ ] **Step 4: Add a migration marker**

Create a small `schema_metadata` table that proves Flyway runs. The complete domain tables remain a separate reviewed Stage 0 task because they require their own schema and migration tests.

- [ ] **Step 5: Run the configuration test and full suite**

Run: `mvn -f backend/pom.xml test`

Expected: every test passes.

### Task 6: Documentation, build verification, and IDEA launch

**Files:**
- Modify: `README.md`
- Create: `backend/README.md`

**Interfaces:**
- Consumes: verified Maven commands and local environment findings.
- Produces: accurate developer startup instructions and an IDEA project opened at the repository root.

- [ ] **Step 1: Document the verified backend commands**

Document Java 17, environment variables, `mvnw.cmd test`, and `mvnw.cmd spring-boot:run`. State that MySQL or matching environment configuration is required for a normal run.

- [ ] **Step 2: Run the full verification suite**

Run from `backend/`: `./mvnw.cmd clean test`

Expected: build exits 0 with zero test failures.

- [ ] **Step 3: Inspect the final repository diff**

Run: `git status --short` and `git diff --check`

Expected: only the planned backend, README, and plan files are changed; whitespace check exits 0.

- [ ] **Step 4: Launch IntelliJ IDEA**

Run: `D:\IntelliJ IDEA 2025.3.4\bin\idea64.exe D:\javaweb\VolunteerFlow`

Expected: IDEA opens the repository and detects `backend/pom.xml` as a Maven project.
