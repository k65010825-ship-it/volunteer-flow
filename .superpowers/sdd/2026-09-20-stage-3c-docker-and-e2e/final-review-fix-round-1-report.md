# Stage 3C holistic review fix round 1

Base: `21e91b2`

## Findings addressed

- Important: the root and SPA deep-link smoke requests now pass `-UseBasicParsing`, with an executable Windows PowerShell 5.1 regression.
- Important: `SPRING_FLYWAY_ENABLED` is required by the shared environment parser, accepts only lowercase `true` or `false`, overrides and restores caller conflicts, and reaches both the Compose backend and Maven E2E fixture.
- Minor: deployment prerequisites now require Node.js 22.13.0 or newer.
- Minor: README test evidence now records the fresh post-fix backend count.

The recovered external database example sets `SPRING_FLYWAY_ENABLED=false` only after checking schema compatibility, backup state, and `flyway_schema_history`. The runbook keeps fresh-database policy separate and forbids deleting, modifying, or fabricating Flyway history. No application-local configuration, live database, or Flyway history was changed.

## TDD and verification evidence

- RED: 19 focused tests ran with 8 expected failures caused by the missing smoke flag, Flyway propagation/validation, and documentation prerequisites.
- GREEN: the same focused set passed 19/19.
- Backend: `mvnw.cmd clean verify -ntp` passed 606/606; the executable JAR was repackaged.
- Frontend: Vitest passed 16 files and 142/142 tests with `--maxWorkers=2`; typecheck and production build passed, transforming 118 modules.
- Playwright discovery: 2 tests in 1 Chromium file were listed with placeholder non-secret URL/UUID values; browser scenarios were not executed.
- PowerShell: all four deployment scripts parsed with 0 errors under Windows PowerShell 5.1 and PowerShell 7.

Docker image builds, Nginx checks, Compose startup, live public smoke, and real browser E2E were not run and are not claimed. The three pre-existing user-owned frontend modifications were excluded from this fix.
