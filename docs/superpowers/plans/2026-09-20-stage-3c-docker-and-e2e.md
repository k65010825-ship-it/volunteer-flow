# Stage 3C Docker Deployment and End-to-End Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the Vue frontend and Java 17 backend behind Nginx, connect them to the user's existing VM MySQL container, and verify the complete Stage 3 flow through the public entrypoint.

**Architecture:** Build separate backend and frontend images. Docker Compose owns only those two services; database connection settings are injected from an ignored `.env`, and no MySQL service or volume is declared. Nginx serves the SPA and proxies `/api` and `/actuator` to the backend after its database-backed readiness probe succeeds.

**Tech Stack:** Docker multi-stage builds, Docker Compose, Eclipse Temurin Java 17, Node.js, Nginx, Spring Boot Actuator, Vue/Vite, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-20-stage-3-notification-checkin-deployment-design.md`

## Global Constraints

- Complete Stage 3A and Stage 3B before executing this plan.
- Compose must not create, delete, rename, restart, or own a MySQL container or volume.
- Do not change `backend/config/application-local.yml` or hardcode the VM address, port, username, or password.
- Use Java 17 in both build and runtime images.
- Commit only `deploy/.env.example`; never commit `deploy/.env` or live credentials.
- Nginx is the only public application entrypoint in the Compose topology.
- `/api` and `/actuator` must reach Spring Boot; all other unknown paths use Vue SPA fallback.
- Browser evidence must use real Nginx and backend endpoints, not mocked API responses.

## Review Focus

- A database password containing shell or URL-special characters must be passed as an environment value, not interpolated into a generated file; Task 3 validates Compose rendering without printing secrets.
- Vue deep links such as `/notifications` must reload successfully instead of returning Nginx 404; Task 2 tests SPA fallback.
- Nginx must not start routing before the backend database readiness probe passes; Task 3 checks the health dependency.
- Proxy headers must preserve client/protocol/host information without exposing internal container names; Task 2 verifies configuration and live headers.
- A failed E2E run must clean only its `s3e2e-` fixtures and must not modify unrelated VM data; Task 4 uses exact IDs and prefixes.

---

## File Structure

```text
backend/
├─ Dockerfile
└─ .dockerignore

frontend/
├─ Dockerfile
├─ playwright.config.ts
└─ e2e/stage3.spec.ts

.dockerignore
deploy/
├─ docker-compose.yml
├─ .env.example
├─ nginx/nginx.conf
├─ smoke-test.ps1
└─ run-stage3-e2e.ps1
```

## Task 1: Package the Java 17 backend

**Files:**
- Create: `backend/Dockerfile`
- Create: `backend/.dockerignore`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: image exposing port 8080 and readiness endpoint `/actuator/health/readiness`.
- Consumes: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, and bootstrap-admin environment variables.

- [ ] **Step 1: Run the missing-image check and verify RED**

```powershell
docker build --file backend/Dockerfile --tag volunteerflow-backend:stage3 backend
```

Expected: FAIL because `backend/Dockerfile` does not exist.

- [ ] **Step 2: Add a Java 17 multi-stage Dockerfile**

Use this structure:

```dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/volunteerflow-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore` excludes `target`, `config/application-local.yml`, logs, IDE files, and `.env` files.

Keep readiness dependent on `readinessState,db`; do not require Redis for Stage 3 readiness.

- [ ] **Step 3: Build the image and inspect its Java version**

```powershell
docker build --file backend/Dockerfile --tag volunteerflow-backend:stage3 backend
docker run --rm --entrypoint java volunteerflow-backend:stage3 -version
```

Expected: build succeeds and reports Java 17.

- [ ] **Step 4: Commit**

```powershell
git add backend/Dockerfile backend/.dockerignore backend/src/main/resources/application.yml
git commit -m "build: package backend with java 17"
```

## Task 2: Package Vue behind Nginx

**Files:**
- Create: `frontend/Dockerfile`
- Create: `.dockerignore`
- Create: `deploy/nginx/nginx.conf`

**Interfaces:**
- Produces: Nginx image listening on port 80.
- Proxies: `/api/` and `/actuator/` to `http://backend:8080`.

- [ ] **Step 1: Run the missing-image check and verify RED**

```powershell
docker build --file frontend/Dockerfile --tag volunteerflow-frontend:stage3 .
```

Expected: FAIL because the Dockerfile and Nginx configuration do not exist.

- [ ] **Step 2: Add frontend multi-stage build and explicit Nginx routing**

Dockerfile:

```dockerfile
FROM node:22-alpine AS build
WORKDIR /workspace
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
```

Nginx requirements:

```nginx
location /api/ {
    proxy_pass http://backend:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /actuator/ {
    proxy_pass http://backend:8080;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

- [ ] **Step 3: Build and test Nginx configuration**

```powershell
docker build --file frontend/Dockerfile --tag volunteerflow-frontend:stage3 .
docker run --rm --entrypoint nginx volunteerflow-frontend:stage3 -t
```

Expected: image builds and Nginx reports that its configuration test is successful.

- [ ] **Step 4: Commit**

```powershell
git add frontend/Dockerfile .dockerignore deploy/nginx/nginx.conf
git commit -m "build: serve frontend through nginx"
```

## Task 3: Compose against the existing VM MySQL container

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/.env.example`
- Create: `deploy/smoke-test.ps1`

**Interfaces:**
- Consumes database and application settings from `deploy/.env`.
- Produces public `APP_PORT` mapped to Nginx only.

- [ ] **Step 1: Run Compose config before creation and verify RED**

```powershell
docker compose --file deploy/docker-compose.yml --env-file deploy/.env.example config
```

Expected: FAIL because `deploy/docker-compose.yml` does not exist.

- [ ] **Step 2: Create the two-service Compose topology**

The service skeleton is:

```yaml
services:
  backend:
    build:
      context: ../backend
    environment:
      DB_HOST: ${DB_HOST:?set DB_HOST}
      DB_PORT: ${DB_PORT:?set DB_PORT}
      DB_NAME: ${DB_NAME:?set DB_NAME}
      DB_USERNAME: ${DB_USERNAME:?set DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET}
    healthcheck:
      test: ["CMD", "curl", "--fail", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s

  frontend-nginx:
    build:
      context: ..
      dockerfile: frontend/Dockerfile
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "${APP_PORT:-8088}:80"
```

Do not add `mysql`, `redis`, named volumes, or host database port mappings.

`.env.example` uses non-secret placeholders and documents that `DB_HOST` may be the VM host address or an existing container DNS name reachable from the Compose network.

- [ ] **Step 3: Write a smoke script that fails on any public-path break**

`deploy/smoke-test.ps1` accepts `-BaseUrl` and performs:

```powershell
$health = Invoke-RestMethod "$BaseUrl/actuator/health/readiness"
if ($health.status -ne 'UP') { throw 'backend readiness is not UP' }
$root = Invoke-WebRequest "$BaseUrl/"
if ($root.StatusCode -ne 200) { throw 'frontend root failed' }
$deep = Invoke-WebRequest "$BaseUrl/notifications"
if ($deep.StatusCode -ne 200 -or $deep.Content -notmatch '<div id="app">') {
  throw 'SPA fallback failed'
}
```

The script must never print environment values or response authorization headers.

- [ ] **Step 4: Validate rendered Compose without exposing credentials**

```powershell
docker compose --file deploy/docker-compose.yml --env-file deploy/.env.example config --quiet
```

Expected: exit code 0. Do not print the full rendered config because it may contain secrets when using a real `.env`.

- [ ] **Step 5: Start the real stack and run smoke verification**

Create ignored `deploy/.env` with the existing MySQL container connection values, then run:

```powershell
docker compose --file deploy/docker-compose.yml --env-file deploy/.env up --build --detach
.\deploy\smoke-test.ps1 -BaseUrl "http://localhost:8088"
docker compose --file deploy/docker-compose.yml --env-file deploy/.env ps
```

Expected: backend is healthy, Nginx is running, frontend root/deep link and readiness all pass. Confirm `docker compose ps` lists only `backend` and `frontend-nginx` for this project.

- [ ] **Step 6: Commit**

```powershell
git add deploy/docker-compose.yml deploy/.env.example deploy/smoke-test.ps1
git commit -m "build: add external-database demo compose"
```

## Task 4: Add real-browser Stage 3 E2E coverage

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/stage3.spec.ts`
- Create: `backend/src/test/java/com/volunteerflow/Stage3E2eFixtureIT.java`
- Create: `deploy/run-stage3-e2e.ps1`

**Interfaces:**
- Consumes a running Nginx public URL from `PLAYWRIGHT_BASE_URL`.
- Consumes an isolated fixture prefix from `S3_E2E_RUN_ID`.
- Produces a wrapper whose `finally` block always runs exact fixture cleanup.

- [ ] **Step 1: Install Playwright test runner and create a failing public-entry test**

```powershell
cd frontend
npm install --save-dev @playwright/test
npx playwright install chromium
```

First test navigates to `/notifications` through Nginx and expects the login page after auth redirect. Run it before adding Playwright configuration; expected result is configuration/test discovery failure.

- [ ] **Step 2: Add Playwright configuration and verify the public-entry test passes**

Configuration uses `PLAYWRIGHT_BASE_URL`, Chromium, trace on first retry, screenshots on failure, and no embedded web server because Compose owns the stack.

```powershell
$env:PLAYWRIGHT_BASE_URL='http://localhost:8088'
npx playwright test e2e/stage3.spec.ts --project=chromium
```

- [ ] **Step 3: Implement exact E2E fixture seed and cleanup**

`Stage3E2eFixtureIT` accepts `stage3.e2e.action=seed|cleanup` and `stage3.e2e.run-id=<uuid>`. Seed creates one organization, administrator, members, activity, positions, and registration states through real services using the exact prefix `s3e2e-<uuid>`. Cleanup queries only rows carrying that exact prefix, deletes child rows before parent rows, and asserts zero matching organizations and users remain. It must reject a blank or non-UUID run ID and must never use `TRUNCATE` or an unscoped delete.

Create `deploy/run-stage3-e2e.ps1` with a `try/finally` lifecycle:

```powershell
param([string]$BaseUrl = 'http://localhost:8088')
$runId = [Guid]::NewGuid().ToString('N')
try {
  Push-Location backend
  .\mvnw.cmd '-Dtest=Stage3E2eFixtureIT' '-Dstage3.e2e.action=seed' "-Dstage3.e2e.run-id=$runId" test
  Pop-Location
  $env:PLAYWRIGHT_BASE_URL = $BaseUrl
  $env:S3_E2E_RUN_ID = $runId
  Push-Location frontend
  npx playwright test e2e/stage3.spec.ts --project=chromium
  Pop-Location
} finally {
  while ((Get-Location).Path -match '\\(frontend|backend)$') { Pop-Location }
  Push-Location backend
  .\mvnw.cmd '-Dtest=Stage3E2eFixtureIT' '-Dstage3.e2e.action=cleanup' "-Dstage3.e2e.run-id=$runId" test
  Pop-Location
}
```

The fixture derives unique test usernames and passwords from the run ID without printing passwords, JWTs, cookies, or the generated check-in code.

- [ ] **Step 4: Add one failing browser scenario at a time for the complete flow**

Use the real browser through Nginx for assertions. Add and run these scenarios sequentially:

1. Member sees a real review/promotion notification and navigation marks it read.
2. Administrator submits an activity change with a reason; member sees the change notification.
3. Administrator opens a 15-minute session; member enters the returned code and sees signed-in state after reload.
4. Administrator manually checks in a second member with a reason and sees updated totals.
5. Ordinary member receives 403/hidden management behavior.

For each scenario, temporarily make one observable expectation stricter than current behavior, run it to see the intended failure, then implement or correct the owned browser-facing behavior and rerun to GREEN. Do not alter backend behavior here without first adding a focused backend RED test.

After the administrator opens the session, keep the returned plaintext code only in the Playwright process, obtain backend logs with `docker compose logs backend`, and assert the exact code is absent. Do not attach the code to traces, screenshots, or test titles.

- [ ] **Step 5: Run the lifecycle wrapper twice to detect state leakage**

```powershell
cd ..
.\deploy\run-stage3-e2e.ps1 -BaseUrl "http://localhost:8088"
.\deploy\run-stage3-e2e.ps1 -BaseUrl "http://localhost:8088"
```

Expected: both runs PASS independently with zero console errors.

- [ ] **Step 6: Commit**

```powershell
git add frontend/package.json frontend/package-lock.json frontend/playwright.config.ts frontend/e2e/stage3.spec.ts backend/src/test/java/com/volunteerflow/Stage3E2eFixtureIT.java deploy/run-stage3-e2e.ps1
git commit -m "test: cover stage 3 through nginx"
```

## Task 5: Final documentation and release verification

**Files:**
- Modify: `README.md`
- Create: `docs/operations/stage-3-demo-deployment.md`

**Interfaces:**
- Produces: an operator runbook that never contains live credentials.

- [ ] **Step 1: Update documentation with exact deployment and rollback commands**

Document prerequisites, copying `.env.example` to ignored `.env`, connecting the existing MySQL container, `up --build --detach`, logs, health checks, and `down`. State explicitly that `down` does not remove or control MySQL because it is external.

Document rollback as checking out the previous application image/commit and restarting only backend/frontend; database migrations are forward-only and must not be manually deleted.

- [ ] **Step 2: Run the complete backend and frontend verification**

```powershell
cd backend
.\mvnw.cmd clean verify
cd ..\frontend
npm test -- --run
npm run typecheck
npm run build
```

Expected: all tests and builds PASS.

- [ ] **Step 3: Run deployment and browser verification**

```powershell
cd ..
docker compose --file deploy/docker-compose.yml --env-file deploy/.env config --quiet
docker compose --file deploy/docker-compose.yml --env-file deploy/.env up --build --detach
.\deploy\smoke-test.ps1 -BaseUrl "http://localhost:8088"
.\deploy\run-stage3-e2e.ps1 -BaseUrl "http://localhost:8088"
```

Expected: Compose, smoke checks, and Playwright PASS.

- [ ] **Step 4: Inspect logs and source for secret/code leakage**

Confirm the E2E assertion found no check-in code in backend logs. Confirm `git status --ignored --short deploy/.env` reports it as ignored, and `git grep` contains no real VM password or JWT secret.

- [ ] **Step 5: Commit documentation and any verified corrections**

```powershell
git add README.md docs/operations/stage-3-demo-deployment.md
git commit -m "docs: add stage 3 deployment runbook"
```

- [ ] **Step 6: Stop only the application stack after verification if the user does not want it left running**

```powershell
docker compose --file deploy/docker-compose.yml --env-file deploy/.env down
```

Expected: only Stage 3 backend/frontend containers and their Compose network stop; the existing MySQL container remains running and unchanged.
