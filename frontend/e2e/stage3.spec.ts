import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { expect, test, type Browser, type Page } from "@playwright/test";

const runId = process.env.S3_E2E_RUN_ID;
if (!runId || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(runId)) {
  throw new Error("S3_E2E_RUN_ID must be a lowercase version-4 UUID");
}

const prefix = `s3e2e-${runId}`;
const activityTitle = `${prefix}-activity`;
const password = `S3!${runId.replaceAll("-", "").slice(0, 12)}aZ9`;

// This file handles a one-time plaintext code. Disable automatic artifacts so the code can never
// be captured after it is opened; the project defaults remain available to non-secret specs.
test.use({ trace: "off", screenshot: "off" });

async function login(page: Page, suffix: "admin" | "member1" | "member2") {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(`${prefix}-${suffix}`);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/organizations\/\d+\/activities$/);
}

async function openFixtureActivity(page: Page) {
  await page.goto("/activities");
  const link = page.locator("a.activity-row").filter({ hasText: activityTitle });
  await expect(link).toHaveCount(1);
  await link.click();
  await expect(page.getByRole("heading", { name: activityTitle })).toBeVisible();
  const match = /\/activities\/(\d+)$/.exec(new URL(page.url()).pathname);
  if (!match) throw new Error("Fixture activity route did not expose an activity ID");
  return match[1];
}

async function newLoggedInPage(
  browser: Browser,
  suffix: "admin" | "member1" | "member2",
) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await login(page, suffix);
  return { context, page };
}

function assertCodeAbsentFromBackendLogs(code: string) {
  const repositoryRoot = resolve(process.cwd(), "..");
  const composeFile = resolve(repositoryRoot, "deploy", "docker-compose.yml");
  const envFile = process.env.STAGE3_COMPOSE_ENV_FILE
    ? resolve(process.env.STAGE3_COMPOSE_ENV_FILE)
    : resolve(repositoryRoot, "deploy", ".env");
  let logs: string;
  try {
    logs = execFileSync(
      "docker",
      [
        "compose",
        "--file",
        composeFile,
        "--env-file",
        envFile,
        "logs",
        "--no-color",
        "backend",
      ],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
    );
  } catch {
    throw new Error("Could not inspect backend logs for plaintext-code leakage");
  }
  if (logs.includes(code)) {
    throw new Error("Backend logs contained plaintext check-in code");
  }
}

test("redirects a protected notification deep link to login through Nginx", async ({ page }) => {
  await page.goto("/notifications");
  await expect(page).toHaveURL(/\/login\?redirect=\/notifications$/);
  await expect(page.getByRole("heading", { name: "欢迎回到 VolunteerFlow" })).toBeVisible();
});

test("completes Stage 3 notifications, change, self check-in, manual check-in and authorization", async ({
  browser,
}) => {
  const member = await newLoggedInPage(browser, "member1");
  const administrator = await newLoggedInPage(browser, "admin");
  const secondMember = await newLoggedInPage(browser, "member2");

  try {
    await member.page.goto("/notifications");
    const reviewNotification = member.page
      .locator('[data-test="notification-item"]')
      .filter({ hasText: "报名审核结果" });
    await expect(reviewNotification).toHaveCount(1);
    await expect(reviewNotification.getByLabel("未读")).toBeVisible();
    await reviewNotification.click();
    await expect(member.page).toHaveURL(/\/registrations\/\d+$/);
    await member.page.goto("/notifications");
    await expect(
      member.page
        .locator('[data-test="notification-item"]')
        .filter({ hasText: "报名审核结果" })
        .getByLabel("未读"),
    ).toHaveCount(0);

    const activityId = await openFixtureActivity(administrator.page);
    await administrator.page.getByRole("link", { name: "管理正式变更" }).click();
    await administrator.page.getByLabel("活动地点").fill("E2E changed assembly point");
    await administrator.page.locator('[data-test="review-activity-change"]').click();
    await administrator.page.locator('[data-test="change-reason"]').fill("Stage 3 browser change verification");
    await administrator.page.locator('[data-test="submit-activity-change"]').click();
    await expect(administrator.page.getByRole("status")).toContainText("已通知 2 位受影响成员");

    await member.page.goto("/notifications");
    await expect(
      member.page.locator('[data-test="notification-item"]').filter({ hasText: "活动变更" }),
    ).toHaveCount(1);

    await administrator.page.goto(`/activities/${activityId}`);
    await administrator.page.getByRole("link", { name: "签到管理" }).click();
    await expect(administrator.page).toHaveURL(`/activities/${activityId}/check-in-management`);
    await administrator.page.locator('[data-test="duration"]').fill("15");
    await administrator.page.locator('[data-test="open-session"]').click();
    const code = (await administrator.page.locator('[data-test="plaintext-code"]').textContent())?.trim();
    if (!code) throw new Error("Open-session response did not expose its one-time code");
    assertCodeAbsentFromBackendLogs(code);
    await administrator.page.getByRole("button", { name: "刷新管理数据" }).click();
    await expect(administrator.page.locator('[data-test="plaintext-code"]')).toHaveCount(0);

    await member.page.goto(`/activities/${activityId}`);
    await expect(member.page.locator('[data-test="checkin-management-link"]')).toHaveCount(0);
    await member.page.getByRole("link", { name: "活动签到" }).click();
    await member.page.locator('[data-test="code"]').fill(code);
    await member.page.locator('[data-test="submit-checkin"]').click();
    await expect(member.page.getByRole("heading", { name: "已签到" })).toBeVisible();
    await member.page.reload();
    await expect(member.page.getByRole("heading", { name: "已签到" })).toBeVisible();

    await administrator.page.getByRole("button", { name: "刷新管理数据" }).click();
    await expect(administrator.page.locator('[data-test="attendance-totals"]')).toContainText("已签到 1");
    await administrator.page.locator('[data-test="member-search"]').fill(`${prefix}-member2`);
    const manualCandidate = administrator.page
      .locator("article.managed-position")
      .filter({ hasText: `${prefix}-member2` });
    await expect(manualCandidate).toHaveCount(1);
    await manualCandidate.getByLabel("补签原因（必填，最多 500 字）").fill("现场身份核验通过");
    await manualCandidate.getByRole("button", { name: "确认补签" }).click();
    await expect(administrator.page.getByRole("status")).toContainText("补签成功");
    await expect(administrator.page.locator('[data-test="attendance-totals"]')).toContainText("已签到 2");

    await openFixtureActivity(secondMember.page);
    await expect(secondMember.page.locator('[data-test="checkin-management-link"]')).toHaveCount(0);
    const denied = await secondMember.page.goto(`/activities/${activityId}/check-in-management`);
    expect(denied?.status()).toBe(200);
    await expect(secondMember.page.getByRole("alert")).toContainText("你没有执行此操作的权限");
  } finally {
    await Promise.all([
      member.context.close(),
      administrator.context.close(),
      secondMember.context.close(),
    ]);
  }
});
