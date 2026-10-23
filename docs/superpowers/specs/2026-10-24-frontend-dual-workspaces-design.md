# VolunteerFlow frontend workspaces

The user requested a complete Vue frontend based on the implemented backend, separated into platform administrator and USER surfaces. Continue the existing Vue 3, TypeScript, Pinia, Axios and Vue Router project; preserve all registration, offer, notification and check-in workflows. No deployment or live VM/database access is authorized by this task.

## Architecture and roles

Use one application with two route/layout groups rather than separate builds or a client-selected security role. The authenticated `/auth/me` platformRole controls the platform administrator entry and route guard. `/admin/organizations` provides list, filter, creation with an existing owner user ID, and confirmed deactivation. USER pages provide organization selection/join, activity discovery/detail/form submission, registration history and offer responses, check-in, notifications and read-only profile.

An organization manager is still a USER with organization-scoped permissions, not a third platform role. The server's current organization access projection gates organization member, invitation, role, activity and audit controls. Platform administrator status does not implicitly confer organization membership. The backend remains the final authorization authority. Route guards hydrate the current user on reload; tokens remain in memory with refresh cookies. Access and request data must be reset on organization changes.

## Interface completion

Add small read-only backend adapters for current organization effective access, existing role permission codes, and saved activity/position question definitions. Add inviteId to the existing invitation creation response so its existing revocation endpoint can be used. Never infer permissions from role names or invent absent user search/profile-edit APIs.

Organization pages support paged member lists, assignment to one role, protected owner restrictions, role CRUD/grants, one-time invite display and revocation, and filtered audit pagination. Activity pages support creation and draft update, adding positions/questions, publication, official changes with reasons, cancellation and manual completion. Existing endpoint constraints govern whether an action exists and in which state it is allowed. Published activity, own registration and member lists consume backend `data[]` plus `meta` without dropping later pages.

## Visual system

Keep existing true white background, navy #102044 text, green #16a05d actions, #e5e9f0 borders, system/PingFang/Microsoft YaHei fonts, 8px input/button corners. Platform management uses a 224px navy sidebar, white content, readable row tables and forms. USER desktop uses a white navigation bar and open activity lists; phone layout uses bottom navigation. Profile is read-only because no edit endpoint exists. No fake dashboard counts or invented analytics.

Concept reference generated 2026-10-24: C:/Users/lenovo/.codex/generated_images/019fe19f-a9e6-7ec1-9f45-ca093e162f31/exec-a6623154-d283-4df0-82da-176cae50957f.png. Functional additions and accurate backend copy override illustrative sample rows and labels in the reference.

## Verification

Run serialized frontend typecheck, production build and Vitest regression. Add tests for platform routing, permission-dependent controls, payloads, paging and stale-response handling. Use browser request interception to exercise real rendered pages with exact backend contract fixtures when the live backend is unavailable; explicitly distinguish these browser checks from live end-to-end integration. Inspect desktop and mobile screenshots, keyboard focus, overflow, empty/error/pending states, authentication restoration and unauthorized routes. Verify backend adapter tests independently using Java 17 without remote services. Preserve unrelated/user changes and stage only task-owned paths.

## Verified delivery

- Frontend: `npm run typecheck`, `npm test -- --maxWorkers=1` (193 passing tests in 25 files), and `npm run build` all passed.
- Backend: Java 17 clean test ran 1004 tests with zero failures/errors/skips. The same seven deployment-script contract classes excluded in the preceding backend delivery remain excluded; no opt-in MySQL/Kafka integration or VM/deployment was run.
- Independent review found three Important issues: organization-switch mutation races, missing organization/audit navigation, and hidden cancellation entry for cancel-only users. Each was fixed and independently marked ADDRESSED; deferred-request and permission-specific regression tests are included.
- Browser plugin was unavailable. Playwright with locally installed Chrome exercised the actual Vite application at `http://127.0.0.1:5174`, with contract-shaped API responses intercepted per browser context. These are browser/UI checks, not live backend or database integration.
- Verified admin create/filter/deactivate; USER/admin route separation; activity and own-registration next pages; profile; manager invitation generation/revocation; audit display; activity draft creation, position save and publication. No page/console errors were observed. Desktop 1440×1000 and mobile 390×844 had no document horizontal overflow.

## Visual comparison and intentional adaptations

The generated concept and final browser screenshots were inspected using `view_image` (concept at original resolution). The comparison checked true-white backgrounds/navy sidebar, green action colors, 8px controls and focus states, Chinese type hierarchy, table/list density and borders, plus mobile navigation/overflow. The concept's sample rows are browser contract fixtures only, never bundled fake records or claimed project metrics.

Intentional adaptations to the existing application: organization creation uses an inline labelled form rather than a drawer; the non-paginated organization API uses local filters without fictitious server pages; user activities retain the established open row lists instead of framed cards; organization switching uses a dedicated organization page; management forms extend the same visual tokens rather than inventing analytics. Existing backend error and permission rules take precedence over illustrative concept labels. Desktop/mobile screenshots are saved outside the repository in the current Codex workspace `output/playwright/`.

## Remaining backend-contract boundaries

- Platform admins manage organizations; they cannot bypass membership to perform organization business.
- Profile is read-only. No unsupported user search, profile update, organization reactivation, or saved-position update/delete operation is invented.
- A custom role with only `activity:create` and without `activity:read` can create but cannot reload draft detail under existing backend policy. The UI retains the saved ID and explains the missing permission; the backend policy was not silently expanded.
- Role metadata and grant updates are separate backend transactions. The form remains retryable if the second request fails. Old organization responses cannot reveal invite codes or issue follow-up requests in a newly selected organization.

## Follow-up: UI consistency and shared back navigation

The user's screenshots exposed browser-default blue/underlined RouterLinks and a full-width form-button rule applied to a creation link. Corrected the global link/button treatment, compact page actions, heading/spacing rhythm and editor related links. Activity lists now use a single intentional content column; the editor's time fields use two columns on desktop and one on phones. No new theme or image generation was needed for these targeted changes to the existing visual system.

Every functional subpage now receives one shared `PageBack` control in `AppShell`, rather than requiring each view to implement navigation. User home, platform home and the authentication entry are entry pages without this control. Registration and all business subpages do have it. A safe prior Vue Router entry uses history back; direct links, external/invalid history and post-login history fall back to the user's or administrator's home. Public registration falls back to login. Removed duplicate view-level back controls while retaining meaningful contextual links.

Verified in the main checkout used by the user at port 5175: typecheck, 223 frontend tests in 26 files, and production build pass. Playwright with intercepted contract fixtures checks home/subpage visibility, actual history return, direct-open USER/admin fallbacks, compact white-text creation links without underlines, green context links, desktop two-column and mobile single-column fields, no horizontal overflow or console errors. Screenshots at 1440px and 390px were visually inspected. No backend, database, Redis address, authentication policy or deployment was changed by this follow-up.
