# Stage 1 Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the runnable Stage 1 loop: authenticated users can join an organization by invite, organization RBAC protects management operations, administrators can create and publish multi-position activities, and members can browse those activities on a responsive Vue client.

**Architecture:** Extend the existing Java 17 modular monolith with focused MyBatis-Plus entities, custom authorization queries, transactional services, and REST controllers. MySQL remains the source of truth; Redis and Kafka are not used by Stage 1 business correctness. A Vue 3 + TypeScript + Vite client stores access tokens only in memory, uses the HttpOnly refresh cookie, and renders the approved mobile concept in `docs/design/stage-1-mobile-concept.png`.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Security 6, MyBatis-Plus 3.5.17, MySQL/Flyway, JUnit 5/MockMvc, Vue 3, TypeScript, Vite, Pinia, Vue Router, Axios, Vitest.

**Spec:** `docs/specs/2026-09-14-volunteerflow-revised-design.md`, sections 3–5 and 10; `docs/presentation/VolunteerFlow校园志愿活动平台完整设计.pptx`, slides 39–45 and 67.

## Global Constraints

- Java source level remains 17; do not introduce physical foreign keys or hand-edit shared schema.
- One organization member has exactly one role. The protected owner role always has all current and future organization permissions.
- Cross-organization resource access returns 404; authenticated but unauthorized same-organization access returns 403.
- Invite codes are random, shown once, and stored only as SHA-256 hashes. Joining and incrementing use count are one transaction under a row lock.
- Activities have `DRAFT`, `PUBLISHED`, `FINISHED`, or `CANCELED` state. Publication requires at least one active position and valid time ordering.
- Published activity list/detail is visible only to active organization members in Stage 1.
- The frontend must handle loading, empty, error, unauthorized, and mobile/desktop responsive states.

---

### Task 1: Finish authentication acceptance and API documentation

**Files:**
- Modify: `backend/README.md`
- Test: existing `backend/src/test/java/com/volunteerflow/infrastructure/security/*Test.java`

**Interfaces:** Existing `/api/v1/auth/register`, `/login`, `/refresh`, `/logout`, and `/me` remain stable.

- [ ] **Step 1: Verify authentication tests fail if JWT validation or refresh replay protection is removed.**
- [ ] **Step 2: Run targeted tests and retain the red/green evidence already produced by Task 3.**
- [ ] **Step 3: Document JWT environment variables, Bearer usage, HttpOnly refresh cookies, and manual request sequence.**
- [ ] **Step 4: Run the complete backend test suite.**
- [ ] **Step 5: Commit authentication acceptance documentation.**

### Task 2: Organization, invitation, and single-role RBAC

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/organization/*`
- Create: `backend/src/main/java/com/volunteerflow/rbac/*`
- Create: `backend/src/main/java/com/volunteerflow/audit/*`
- Test: `backend/src/test/java/com/volunteerflow/organization/OrganizationServiceTest.java`
- Test: `backend/src/test/java/com/volunteerflow/rbac/OrganizationAuthorizationServiceTest.java`

**Interfaces:**
- `POST /api/v1/organizations` creates an organization for a supplied owner user ID; platform administrators only.
- `GET /api/v1/organizations` and `GET /api/v1/organizations/{id}` return organizations visible to the current user.
- `POST /api/v1/organizations/{id}/invitations` returns the raw invite code once.
- `POST /api/v1/organization-memberships` consumes a raw invite code idempotently.
- `GET /api/v1/organizations/{id}/members`, `PATCH .../members/{memberId}/role`, `GET .../roles`, `POST .../roles`, and `PUT .../roles/{roleId}/permissions` expose single-role RBAC management.

- [ ] **Step 1: Write service tests for default role creation, invite hashing/idempotency, owner-all-permissions, ordinary denial, role assignment, cross-organization hiding, and audit writes.**
- [ ] **Step 2: Run the organization/RBAC tests and verify failure because services do not exist.**
- [ ] **Step 3: Implement entities, mappers, row-lock invite lookup, authorization service, transactional services, controllers, validation DTOs, and append-only audit writes.**
- [ ] **Step 4: Run targeted tests and the full backend suite.**
- [ ] **Step 5: Commit the organization/RBAC slice.**

### Task 3: Activity and position lifecycle

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/activity/*`
- Test: `backend/src/test/java/com/volunteerflow/activity/ActivityServiceTest.java`
- Test: `backend/src/test/java/com/volunteerflow/activity/ActivityControllerTest.java`

**Interfaces:**
- `POST /api/v1/organizations/{orgId}/activities` creates a draft.
- `PUT /api/v1/activities/{id}` edits a draft; `POST /api/v1/activities/{id}/positions` adds a position.
- `POST /api/v1/activities/{id}/publication` publishes only a valid draft with positions.
- `POST /api/v1/activities/{id}/cancellation` cancels a draft or published activity.
- `GET /api/v1/organizations/{orgId}/activities` lists visible published activities; `GET /api/v1/activities/{id}` returns activity plus positions.

- [ ] **Step 1: Write tests for permission enforcement, time validation, position constraints, publication preconditions, cancellation, organization isolation, and member list/detail projections.**
- [ ] **Step 2: Run targeted tests and verify red.**
- [ ] **Step 3: Implement entities, mappers, DTOs, transactional lifecycle service, controllers, and audit records.**
- [ ] **Step 4: Run targeted tests and the full backend suite.**
- [ ] **Step 5: Commit the activity slice.**

### Task 4: Vue authentication and responsive activity browsing

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/src/*`
- Test: `frontend/src/api/http.test.ts`, `frontend/src/views/ActivityListView.test.ts`
- Modify: root `README.md`, `backend/README.md`

**Interfaces:** The client calls the stable auth and activity APIs above. Axios sends Bearer access tokens, retries a 401 once through `/api/v1/auth/refresh` with credentials, and returns to login when refresh fails.

- [ ] **Step 1: Scaffold Vue 3 + TypeScript + Vite with Router, Pinia, Axios, Vitest, and Vue Test Utils.**
- [ ] **Step 2: Write failing tests for one-time token refresh and activity loading/empty/error states.**
- [ ] **Step 3: Implement login/register, invitation join, activity list, activity detail, shared shell, responsive design tokens, and Vite `/api` proxy.**
- [ ] **Step 4: Run frontend tests, type check, build, and browser verification at desktop and 390×844 mobile sizes against the approved concept.**
- [ ] **Step 5: Update README status and commit the frontend Stage 1 slice.**

### Task 5: Stage 1 end-to-end acceptance

**Files:**
- Modify: `README.md`, `backend/README.md`
- Test: backend and frontend test suites

**Interfaces:** The acceptance path is: platform admin creates organization and owner → owner creates invite → member joins → authorized user creates positions and publishes activity → member views list/detail from the responsive frontend.

- [ ] **Step 1: Start the backend against the configured VM MySQL without changing its host or port.**
- [ ] **Step 2: Start the Vite client and exercise the complete Stage 1 path with disposable acceptance records.**
- [ ] **Step 3: Verify unauthorized and cross-organization requests are rejected and record any environment-only limitation.**
- [ ] **Step 4: Run `backend/mvnw.cmd clean verify`, frontend tests/typecheck/build, and `git diff --check`.**
- [ ] **Step 5: Request code review, fix all blocking findings, and commit the final documentation.**
