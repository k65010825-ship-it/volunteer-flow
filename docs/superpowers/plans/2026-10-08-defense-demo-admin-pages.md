# Defense Demo Admin Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the smallest credible administrator UI needed to demonstrate the implemented organization, RBAC, activity, position, question, publication, registration, waitlist, and review business loop from the running `main` checkout.

**Architecture:** Add typed Axios adapters and two focused Vue pages over existing Spring Boot APIs; do not change backend behavior or schema. The activity page is a deliberate draft-creation wizard because the existing list API exposes only published activities. The organization page supports invitation creation and member-role assignment using built-in roles.

**Tech Stack:** Vue 3, TypeScript, Vue Router, Axios, Vitest, existing Spring Boot APIs.

**Spec:** `docs/specs/2026-08-06-campus-volunteer-platform-design.md`

## Global Constraints

- Work only in `D:\javaweb\VolunteerFlow` main checkout; do not touch the Stage 3 worktree.
- Do not change backend Java, migrations, database configuration, or VM services.
- Backend RBAC remains authoritative; front-end entries may be visible but 403 responses must render a clear permission message.
- Preserve IDs as strings and reuse the global Axios/Jackson conventions.
- Activity creation must preserve strict time ordering and publish only after at least one position exists.
- Run RED-GREEN for each page, then all frontend tests, typecheck, build, and rendered browser checks.

## Review Focus

- A rejected create/add/publish request must keep the current wizard state and show a retryable error.
- Double submission must be disabled while a mutation is in flight.
- Question options are required only for single/multiple choice questions and are sent as trimmed non-empty values.
- Invitation plaintext is shown after creation without being written to browser storage.
- Member role assignment uses the selected role ID and reloads server state after success.

## Task 1: Activity creation, staffing, questions, and publication

**Files:**
- Modify: `frontend/src/api/activities.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/views/ActivityCreationView.vue`
- Create: `frontend/src/views/ActivityCreationView.test.ts`

**Interfaces:**
- Produces route-ready page accepting `organizationId: EntityId`.
- Produces API functions `createActivity`, `addPosition`, `createActivityQuestion`, `createPositionQuestion`, and `publishActivity`.

- [ ] Write tests for local time-order validation, create-draft flow, add-position flow, common/position question creation, publish navigation, mutation disabling, and API errors.
- [ ] Run the focused test and verify RED because the page does not exist.
- [ ] Implement the typed API functions and three-step activity wizard.
- [ ] Run the focused test and verify GREEN.

## Task 2: Organization invitation and member-role management

**Files:**
- Modify: `frontend/src/api/organizations.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/views/OrganizationManagementView.vue`
- Create: `frontend/src/views/OrganizationManagementView.test.ts`

**Interfaces:**
- Produces route-ready page accepting `organizationId: EntityId`.
- Produces API functions `getOrganization`, `listOrganizationMembers`, `listOrganizationRoles`, `createInvitation`, and `assignMemberRole`.

- [ ] Write tests for initial organization/member/role loading, invitation creation, role assignment/reload, 403 display, and stale organization response isolation.
- [ ] Run the focused test and verify RED because the page does not exist.
- [ ] Implement typed adapters and the management page without persisting invitation codes.
- [ ] Run the focused test and verify GREEN.

## Task 3: Routes and visible administrator entries

**Files:**
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/ActivityListView.vue`
- Modify: `frontend/src/views/ActivityListView.test.ts`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes the two route-ready pages from Tasks 1-2.
- Produces `/organizations/:organizationId/activities/new` and `/organizations/:organizationId/manage`.

- [ ] Write failing ActivityList tests for both administrator links and route targets.
- [ ] Add routes, entries, responsive styles, and clear 403 copy.
- [ ] Run ActivityList plus both new page tests.

## Task 4: Defense verification

**Files:**
- Modify only if verification reveals a covered defect.

- [ ] Run `npm test -- --run`.
- [ ] Run `npm run typecheck`.
- [ ] Run `npm run build`.
- [ ] Verify both administrator pages at `http://127.0.0.1:5173` with mocked API responses in a real browser at desktop and mobile widths.
- [ ] Record any flow that still requires prepared data or Swagger instead of claiming it works.
