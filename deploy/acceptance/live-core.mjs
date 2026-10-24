/** Real HTTP + Chrome acceptance. No route interception, fake clock, or test profile.
 * Usage: node live-core.mjs <restricted-runtime-manifest.json> <artifact-directory>
 * Manifest: runId, baseUrl, frontendUrl, adminUsername, adminPassword.
 * Run only against a newly created vf_acceptance_<uuid> database via the isolated runner.
 * Credentials never enter reports, screenshots, traces, or error output.
 */
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { randomBytes } from "node:crypto";
import { verifyAcceptanceIdentity } from "./live-core-safety.mjs";
const require = createRequire(
  new URL("../../frontend/package.json", import.meta.url),
);
const { chromium, request, expect, selectors } = require("@playwright/test");
selectors.setTestIdAttribute("data-test");
const manifest = JSON.parse(await readFile(process.argv[2], "utf8"));
manifest.frontendUrl ||= "http://127.0.0.1:5176";
const output = resolve(process.argv[3]);
if (!/^[0-9a-f-]{36}$/.test(manifest.runId))
  throw new Error("Invalid acceptance run identity");
for (const url of [manifest.baseUrl, manifest.frontendUrl]) {
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url))
    throw new Error("Acceptance must use explicit loopback URLs");
}
await mkdir(output, { recursive: true });
const checks = [];
const contexts = [];
const browserFailures = [];
const browserHttpFailures = [];
let browser;
let currentStep = "initialization";
const report = {
  runId: manifest.runId,
  startedAt: new Date().toISOString(),
  checks,
};
function check(condition, name) {
  if (!condition) throw new Error(name);
  checks.push({ name, passed: true });
}
async function client() {
  const context = await request.newContext({ baseURL: manifest.baseUrl });
  contexts.push(context);
  return { context, token: "" };
}
async function call(client, method, path, data, expected = 200) {
  const response = await client.context.fetch(`/api/v1${path}`, {
    method,
    data,
    headers: client.token ? { Authorization: `Bearer ${client.token}` } : {},
  });
  // Do not print response bodies: they may contain tokens or one-time codes.
  if (response.status() !== expected)
    throw new Error(
      `HTTP ${method} ${path.replace(/\d+/g, ":id")} expected ${expected}, received ${response.status()}`,
    );
  // Security-filter rejections may intentionally have no JSON body.
  return expected === 204 || expected >= 400
    ? undefined
    : await response.json();
}
async function login(client, username, password) {
  // A revoked bearer must not cause the resource-server filter to reject a public login.
  client.token = "";
  client.token = (
    await call(client, "POST", "/auth/login", { username, password })
  ).data.accessToken;
}
async function browserLogin(page, username, password) {
  page.on("pageerror", () => browserFailures.push("pageerror"));
  page.on("response", (response) => {
    if (response.status() >= 400)
      browserHttpFailures.push({
        path: new URL(response.url()).pathname.replace(/\d+/g, ":id"),
        status: response.status(),
      });
  });
  await page.goto(`${manifest.frontendUrl}/login`);
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.waitForURL((url) => !url.pathname.startsWith("/login"));
}
try {
  currentStep = "read-only backend isolation identity";
  const probe = await client();
  const isolation = await call(probe, "GET", "/acceptance/identity");
  verifyAcceptanceIdentity(manifest.runId, isolation.data);
  check(
    true,
    "backend identity matches exact acceptance database before login or business writes",
  );
  currentStep = "account and credential rotation";
  const admin = await client();
  await login(admin, manifest.adminUsername, manifest.adminPassword);
  const identity = (await call(admin, "GET", "/auth/me")).data;
  const members = [];
  const nonce = randomBytes(4).toString("hex");
  for (let index = 1; index <= 2; index++) {
    const account = await client();
    const username = `acc-${manifest.runId.slice(0, 8)}-${nonce}-${index}`;
    const password = randomBytes(18).toString("base64url");
    const payload = {
      username,
      password,
      realName: `验收成员${index}`,
      studentNumber: `${nonce}${index}`,
      contact: "acceptance@example.invalid",
    };
    account.token = (
      await call(account, "POST", "/auth/register", payload, 201)
    ).data.accessToken;
    members.push({ ...account, username, password });
  }
  browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.CHROME_PATH ||
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
  });
  const userContext = await browser.newContext();
  const userPage = await userContext.newPage();
  currentStep = "browser profile update";
  await browserLogin(userPage, members[0].username, members[0].password);
  currentStep = "browser opens profile form";
  await userPage.goto(`${manifest.frontendUrl}/profile`);
  await userPage.getByLabel("姓名", { exact: true }).fill("资料验收成员");
  await userPage
    .getByLabel("联系方式", { exact: true })
    .fill("updated@example.invalid");
  await userPage.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(userPage.getByRole("status")).toContainText("资料已保存");
  check(
    (await call(members[0], "GET", "/auth/me")).data.realName ===
      "资料验收成员",
    "browser profile update persisted to real backend",
  );
  // Capture layout before any password field is filled.
  await userPage.screenshot({
    path: resolve(output, "profile-desktop.png"),
    fullPage: true,
  });
  await userPage.setViewportSize({ width: 390, height: 844 });
  check(
    await userPage.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    "profile form fits 390px viewport",
  );
  await userPage.screenshot({
    path: resolve(output, "profile-mobile.png"),
    fullPage: true,
  });
  const newPassword = randomBytes(18).toString("base64url");
  currentStep = "browser password rotation";
  await userPage
    .getByLabel("当前密码", { exact: true })
    .fill(members[0].password);
  await userPage.getByLabel("新密码", { exact: true }).fill(newPassword);
  await userPage.getByLabel("确认新密码", { exact: true }).fill(newPassword);
  await userPage
    .getByRole("button", { name: "修改密码并重新登录", exact: true })
    .click();
  await userPage.waitForURL("**/login?passwordChanged=1");
  await call(members[0], "GET", "/auth/me", undefined, 401);
  // The independent pre-change session owns an old refresh cookie as well as an old JWT.
  await call(
    { ...members[0], token: "" },
    "POST",
    "/auth/refresh",
    undefined,
    401,
  );
  await call(
    { ...members[0], token: "" },
    "POST",
    "/auth/login",
    { username: members[0].username, password: members[0].password },
    401,
  );
  members[0].password = newPassword;
  await login(members[0], members[0].username, newPassword);
  check(
    true,
    "password rotation rejects old JWT, old refresh cookie and old password; new login succeeds",
  );
  await userContext.close();

  currentStep = "organization and draft browser flow";
  const org = (
    await call(
      admin,
      "POST",
      "/organizations",
      {
        name: `验收-${manifest.runId}-${nonce}`,
        description: "Isolated acceptance data",
        ownerUserId: identity.id,
      },
      201,
    )
  ).data;
  const invite = (
    await call(
      admin,
      "POST",
      `/organizations/${org.id}/invitations`,
      { maxUses: 2 },
      201,
    )
  ).data;
  for (const member of members)
    await call(
      member,
      "POST",
      "/organization-memberships",
      { code: invite.code },
      201,
    );
  const ownerContext = await browser.newContext();
  const page = await ownerContext.newPage();
  page.on("dialog", (dialog) => dialog.accept());
  await browserLogin(page, manifest.adminUsername, manifest.adminPassword);
  currentStep = "browser creates activity draft";
  await page.goto(
    `${manifest.frontendUrl}/organizations/${org.id}/activities/new`,
  );
  const now = Date.now();
  const time = (minutes) =>
    new Date(now + minutes * 60_000).toISOString().slice(0, 16);
  const title = `真实联调-${nonce}`;
  await page.getByLabel("活动标题", { exact: true }).fill(title);
  await page
    .getByLabel("活动介绍", { exact: true })
    .fill("真实后端报名候补签到验收");
  await page.getByLabel("活动地点", { exact: true }).fill("验收集合点");
  for (const [label, minutes] of [
    ["报名开始时间", -60],
    ["报名截止时间", 60],
    ["免费取消截止时间", 90],
    ["活动开始时间", 120],
    ["活动结束时间", 240],
  ]) {
    await page.getByLabel(label, { exact: true }).fill(time(minutes));
  }
  await page.getByTestId("save-activity-draft").click();
  await expect(page.getByRole("status")).toContainText("草稿已保存");
  const drafts = await call(
    admin,
    "GET",
    `/organizations/${org.id}/activity-drafts?page=1&size=20`,
  );
  const activity = drafts.data.find(
    (item) => item.activity.title === title,
  )?.activity;
  check(!!activity, "browser-created draft appears in server-side scoped list");
  await call(
    members[0],
    "GET",
    `/activities/${activity.id}/draft`,
    undefined,
    403,
  );
  await page.goto(`${manifest.frontendUrl}/organizations/${org.id}/drafts`);
  await page.getByRole("link").filter({ hasText: title }).click();
  currentStep = "browser clears draft optional deadline";
  await page.getByLabel("免费取消截止时间", { exact: true }).fill("");
  await page.getByTestId("save-activity-draft").click();
  await expect(page.getByRole("status")).toContainText("草稿已保存");
  check(
    (await call(admin, "GET", `/activities/${activity.id}/draft`)).data.activity
      .freeCancelDeadlineAt == null,
    "clearing optional draft deadline persists SQL NULL",
  );
  await page.getByLabel("岗位名称", { exact: true }).fill("礼仪岗");
  currentStep = "browser creates draft position";
  await page.getByLabel("岗位说明", { exact: true }).fill("礼仪接待");
  await page.getByLabel("容量", { exact: true }).fill("2");
  await page.getByLabel("集合地点", { exact: true }).fill("待清空集合点");
  await page.getByTestId("save-position").click();
  await expect(
    page.getByRole("heading", { name: "礼仪岗 · 2 人" }),
  ).toBeVisible();
  let detail = (await call(admin, "GET", `/activities/${activity.id}/draft`))
    .data;
  const positionId = detail.positions[0].id;
  await page.getByTestId(`edit-position-${positionId}`).click();
  currentStep = "browser edits draft position";
  await page.getByLabel("容量", { exact: true }).fill("1");
  await page.getByLabel("集合地点", { exact: true }).fill("");
  await page.getByTestId("save-position").click();
  await expect(
    page.getByRole("heading", { name: "礼仪岗 · 1 人" }),
  ).toBeVisible();
  await page.getByLabel("岗位名称", { exact: true }).fill("待删除岗位");
  check(
    (await call(admin, "GET", `/activities/${activity.id}/draft`)).data
      .positions[0].meetingLocation == null,
    "clearing optional position meeting location persists SQL NULL",
  );
  await page.getByLabel("岗位说明", { exact: true }).fill("仅验证草稿逻辑删除");
  await page.getByTestId("save-position").click();
  await expect(page.getByRole("heading", { name: /待删除岗位/ })).toBeVisible();
  detail = (await call(admin, "GET", `/activities/${activity.id}/draft`)).data;
  const removed = detail.positions.find(
    (position) => position.name === "待删除岗位",
  );
  await page.getByTestId(`delete-position-${removed.id}`).click();
  currentStep = "browser deletes draft position and creates question";
  await expect(page.getByRole("heading", { name: /待删除岗位/ })).toHaveCount(
    0,
  );
  await page.getByLabel("标题", { exact: true }).fill("报名动机");
  await call(
    admin,
    "GET",
    `/positions/${removed.id}/questions`,
    undefined,
    404,
  );
  await page.getByLabel("必填", { exact: true }).check();
  await page.getByTestId("save-question").click();
  await expect(
    page.locator("li").filter({ hasText: "报名动机" }),
  ).toBeVisible();
  const reviewPosition = (
    await call(
      admin,
      "POST",
      `/activities/${activity.id}/positions`,
      {
        name: "讲解岗",
        description: "人工审核验收",
        capacity: 1,
        registrationMode: "REVIEW",
        promotionTimeoutMinutes: 120,
      },
      201,
    )
  ).data;
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "礼仪岗 · 1 人" }),
  ).toBeVisible();
  await page.screenshot({
    path: resolve(output, "draft-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  check(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    "draft editor fits 390px viewport",
  );
  await page.screenshot({
    path: resolve(output, "draft-mobile.png"),
    fullPage: true,
  });
  await page.getByTestId("publish-activity").click();
  currentStep = "browser publishes draft";
  await expect(page.getByRole("status")).toContainText("活动已发布");
  await call(
    admin,
    "PUT",
    `/positions/${positionId}/draft`,
    {
      name: "禁止改动",
      description: "blocked",
      capacity: 1,
      registrationMode: "FIRST_COME",
    },
    409,
  );
  check(
    true,
    "browser reopens server draft, edits/deletes positions, persists on reload and publishes; published mutation rejected",
  );

  currentStep = "registration waitlist checkin and audit real HTTP";
  const questions = (
    await call(admin, "GET", `/activities/${activity.id}/questions`)
  ).data;
  const answers = [
    {
      questionScope: "ACTIVITY",
      questionId: questions[0].id,
      answer: "愿意服务校园",
    },
  ];
  const registrations = [];
  const memberPages = [];
  for (const member of members) {
    currentStep = "browser submits dynamic registration form";
    const context = await browser.newContext();
    const memberPage = await context.newPage();
    memberPages.push(memberPage);
    await browserLogin(memberPage, member.username, member.password);
    await memberPage.goto(`${manifest.frontendUrl}/activities/${activity.id}`);
    await memberPage.getByRole("radio", { name: /礼仪岗/ }).check();
    await memberPage.getByLabel(/^报名动机/).fill("愿意服务校园");
    const submitted = memberPage.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().endsWith(`/activities/${activity.id}/registrations`),
    );
    await memberPage.getByTestId("submit-registration").click();
    const response = await submitted;
    check(
      response.status() === 201,
      "browser submits a real dynamic registration form",
    );
    const registration = (await response.json()).data;
    registrations.push(registration);
    await memberPage.waitForURL(
      `**/registrations/${registration.registrationId}`,
    );
  }
  const [first, second] = registrations;
  check(
    first.status === "CONFIRMED" && second.status === "WAITLISTED",
    "capacity one yields confirmed first member and waitlisted second member",
  );
  await call(
    members[0],
    "POST",
    `/registrations/${first.registrationId}/cancellation`,
    { reason: "验收取消" },
  );
  const own = (
    await call(members[1], "GET", `/registrations/${second.registrationId}`)
  ).data;
  check(
    own.pendingOffer?.status === "PENDING",
    "cancellation produces a real pending promotion offer",
  );
  await memberPages[1].getByTestId("refresh").click();
  currentStep = "browser accepts promotion offer";
  await memberPages[1].getByTestId("accept").click();
  await expect(memberPages[1].locator(".status-badge")).toContainText("已录取");
  const confirmed = (
    await call(members[1], "GET", `/registrations/${second.registrationId}`)
  ).data;
  check(
    confirmed.status === "CONFIRMED",
    "promotion acceptance confirms the waiting member",
  );
  const review = (
    await call(
      members[0],
      "POST",
      `/activities/${activity.id}/registrations`,
      { positionId: reviewPosition.id, answers },
      201,
    )
  ).data;
  check(
    review.status === "PENDING_REVIEW",
    "review position does not auto-confirm submissions",
  );
  await call(
    admin,
    "POST",
    `/registrations/${review.registrationId}/review-decisions`,
    { decision: "CONFIRM", reason: "符合岗位要求" },
  );
  const session = (
    await call(
      admin,
      "POST",
      `/activities/${activity.id}/check-in-sessions`,
      { durationMinutes: 15, sessionType: "STATIC_CODE" },
      201,
    )
  ).data;
  await memberPages[1].goto(
    `${manifest.frontendUrl}/activities/${activity.id}/check-in`,
  );
  currentStep = "browser self checkin";
  await memberPages[1].getByTestId("code").fill(session.code);
  await memberPages[1].getByTestId("submit-checkin").click();
  await expect(
    memberPages[1].getByRole("heading", { name: "已签到", exact: true }),
  ).toBeVisible();
  await call(
    admin,
    "POST",
    `/check-in-sessions/${session.sessionId}/manual-check-ins`,
    { registrationId: review.registrationId, reason: "验收人工补签" },
    201,
  );
  await call(admin, "POST", `/check-in-sessions/${session.sessionId}/closure`);
  await call(admin, "POST", `/activities/${activity.id}/completion`, {
    reason: "真实联调手动结束",
  });
  const summary = (
    await call(admin, "GET", `/activities/${activity.id}/attendance-summary`)
  ).data;
  check(
    Number(summary.checkedInCount) === 2 && summary.finalStatistics === true,
    "self and manual checkin with approval produce final attendance statistics",
  );
  const audit = await call(
    admin,
    "GET",
    `/organizations/${org.id}/audit-logs?page=1&size=100`,
  );
  check(
    Array.isArray(audit.data) && audit.data.length >= 8,
    "organization audit endpoint exposes persisted operation trail",
  );
  await page.goto(`${manifest.frontendUrl}/activities/${activity.id}`);
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  check(true, "published activity is visible through browser and real API");
  check(
    browserFailures.length === 0,
    "browser workflows have no uncaught JavaScript errors",
  );
  report.status = "PASSED_CORE";
  report.notificationDelivery =
    "NOT_VERIFIED: Kafka relay and consumer disabled in isolated acceptance; Outbox persistence is not delivery";
} catch (error) {
  report.status = "FAILED";
  report.failedStep = currentStep;
  report.browserHttpFailures = browserHttpFailures;
  report.errorType = error?.name;
  // The operation category is useful without serializing arguments or input values.
  report.browserOperation = String(error?.message || "").match(
    /(?:locator|page)\.[a-zA-Z]+/,
  )?.[0];
  // Only our own HTTP error format is safe; Playwright diagnostics can include page input values.
  report.failure = String(error?.message || "").startsWith("HTTP ")
    ? error.message
    : "Acceptance assertion failed; inspect the named step without logging credentials";
  process.exitCode = 1;
} finally {
  await browser?.close();
  for (const context of contexts) await context.dispose();
  report.finishedAt = new Date().toISOString();
  await writeFile(
    resolve(output, "live-core-report.json"),
    JSON.stringify(report, null, 2),
  );
  console.log(
    JSON.stringify({
      status: report.status,
      passedChecks: checks.length,
      failedStep: report.failedStep,
      report: resolve(output, "live-core-report.json"),
    }),
  );
}
