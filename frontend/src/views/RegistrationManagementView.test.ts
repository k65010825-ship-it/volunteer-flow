import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, describe, expect, it } from "vitest";
import { apiClient } from "../api/http";
import type { ManagedRegistration } from "../api/registrations";
import { activityDetail, question } from "../test/registrationFixtures";
import RegistrationManagementView from "./RegistrationManagementView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const row: ManagedRegistration = {
  registrationId: "30",
  organizationId: "1",
  activityId: "10",
  cycleId: "40",
  positionId: "21",
  cycleNumber: 1,
  userId: "7",
  realName: "小明",
  studentNumber: "2026001",
  contact: "本人提供的联系方式",
  status: "PENDING_REVIEW",
  waitlistSequence: null,
  submittedAt: "2026-09-18T08:00:00",
  reviewedBy: null,
  reviewedAt: null,
  reviewReason: null,
  answers: [{ questionScope: "ACTIVITY", questionId: "1", answer: ["可以"] }],
  offer: null,
};
function page(items = [row], total = items.length) {
  return { data: { items, total: String(total), page: "1", size: "20" } };
}
async function open() {
  mock.onGet("/api/v1/activities/10/registration-form").reply(200, {
    data: {
      activity: activityDetail.activity,
      position: activityDetail.positions[1],
      questions: [question()],
    },
  });
  const wrapper = mount(RegistrationManagementView, {
    props: { positionId: "21" },
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
});
describe("RegistrationManagementView", () => {
  it("ignores an old command after navigating away and back", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page());
    mock.onGet("/api/v1/positions/22/registrations").reply(200, page([]));
    let finish!: (value: [number, object]) => void;
    mock.onPost().reply(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const wrapper = await open();
    await wrapper.get("textarea").setValue("旧页面原因");
    await wrapper.get('[data-test="confirm"]').trigger("click");
    await wrapper.setProps({ positionId: "22" });
    await flushPromises();
    await wrapper.setProps({ positionId: "21" });
    await flushPromises();
    finish([409, { code: "POSITION_CAPACITY_FULL" }]);
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(
      mock.history.get.filter((r) => r.url?.endsWith("/registrations")),
    ).toHaveLength(3);
  });

  it("accepts 500 trimmed characters but rejects 501 without sending", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page());
    mock.onPost().reply(503);
    const wrapper = await open();
    await wrapper.get("textarea").setValue("x".repeat(501));
    await wrapper.get('[data-test="waitlist"]').trigger("click");
    expect(mock.history.post).toHaveLength(0);
    await wrapper.get("textarea").setValue("  " + "x".repeat(500) + "  ");
    await wrapper.get('[data-test="waitlist"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0]!.data).reason).toBe("x".repeat(500));
    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "  " + "x".repeat(500) + "  ",
    );
  });

  it("hides promotion for existing offers and safely renders submitted HTML", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(
      200,
      page([
        {
          ...row,
          status: "WAITLISTED",
          answers: [
            {
              questionScope: "ACTIVITY",
              questionId: "1",
              answer: '<img src=x onerror="alert(1)">',
            },
          ],
          offer: {
            id: "90",
            status: "PENDING",
            expiresAt: "2026-09-19T00:00:00",
            reason: null,
            respondedAt: null,
            createdBy: "1",
          },
        },
      ]),
    );
    const wrapper = await open();
    expect(wrapper.find('[data-test="promote"]').exists()).toBe(false);
    expect(wrapper.find("img").exists()).toBe(false);
    expect(wrapper.text()).toContain('<img src=x onerror="alert(1)">');
  });

  it("keeps actions and question ID fallbacks when enrichment is unavailable", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page());
    mock.onGet("/api/v1/activities/10/registration-form").replyOnce(404);
    const wrapper = await open();
    expect(wrapper.text()).toContain("活动问题 #1");
    expect(wrapper.find('[data-test="confirm"]').exists()).toBe(true);
  });
  it("shows readable answers and only pending review decisions", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page());
    const wrapper = await open();
    expect(wrapper.text()).toContain("可参加培训吗");
    expect(wrapper.text()).toContain("可以");
    for (const decision of ["confirm", "waitlist", "reject"]) {
      expect(wrapper.find(`[data-test="${decision}"]`).exists()).toBe(true);
    }
    expect(wrapper.find('[data-test="promote"]').exists()).toBe(false);
  });
  it("requires a trimmed reason and refreshes authoritative state after success", async () => {
    mock.onGet("/api/v1/positions/21/registrations").replyOnce(200, page());
    mock
      .onGet("/api/v1/positions/21/registrations")
      .reply(200, page([{ ...row, status: "CONFIRMED" }]));
    mock.onPost("/api/v1/registrations/30/review-decisions").reply(200, {
      data: { registrationId: "30", cycleId: "40", status: "CONFIRMED" },
    });
    const wrapper = await open();
    await wrapper.get('[data-test="confirm"]').trigger("click");
    expect(mock.history.post).toHaveLength(0);
    expect(wrapper.get('[role="alert"]').text()).toContain("原因");
    await wrapper.get("textarea").setValue("  符合岗位要求  ");
    await wrapper.get('[data-test="confirm"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0]!.data)).toEqual({
      decision: "CONFIRM",
      reason: "符合岗位要求",
    });
    expect(wrapper.text()).toContain("已录取");
    expect(wrapper.find('[data-test="confirm"]').exists()).toBe(false);
  });
  it("preserves failed reasons, prevents duplicate requests, and refreshes after conflict", async () => {
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page());
    let finish!: (value: [number, object]) => void;
    mock.onPost().reply(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const wrapper = await open();
    await wrapper.get("textarea").setValue("保留这个原因");
    await wrapper.get('[data-test="confirm"]').trigger("click");
    await wrapper.get('[data-test="reject"]').trigger("click");
    expect(mock.history.post).toHaveLength(1);
    finish([409, { code: "POSITION_CAPACITY_FULL" }]);
    await flushPromises();
    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "保留这个原因",
    );
    expect(
      mock.history.get.filter((r) => r.url?.endsWith("/registrations")),
    ).toHaveLength(2);
    expect(wrapper.get('[role="alert"]').text()).toContain("状态");
  });
  it("only promotes unordered candidates without an existing offer", async () => {
    mock
      .onGet("/api/v1/positions/21/registrations")
      .reply(200, page([{ ...row, status: "WAITLISTED" }]));
    mock.onPost("/api/v1/registrations/30/promotion-offers").reply(201, {
      data: {
        id: "90",
        status: "PENDING",
        expiresAt: "2026-09-19T00:00:00",
        reason: null,
      },
    });
    const wrapper = await open();
    expect(wrapper.find('[data-test="confirm"]').exists()).toBe(false);
    await wrapper.get('[data-test="promote"]').trigger("click");
    await flushPromises();
    expect(mock.history.post[0]!.url).toBe(
      "/api/v1/registrations/30/promotion-offers",
    );
    mock
      .onGet("/api/v1/positions/21/registrations")
      .reply(
        200,
        page([{ ...row, status: "WAITLISTED", waitlistSequence: "1" }]),
      );
    await wrapper.get('[data-test="refresh"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="promote"]').exists()).toBe(false);
  });
  it("bounds pagination and resets it when filtering", async () => {
    mock
      .onGet("/api/v1/positions/21/registrations")
      .reply(200, page([row], 41));
    const wrapper = await open();
    expect(
      wrapper.get('[data-test="previous"]').attributes("disabled"),
    ).toBeDefined();
    await wrapper.get('[data-test="next"]').trigger("click");
    await flushPromises();
    expect(
      mock.history.get.filter((r) => r.url?.endsWith("/registrations")).at(-1)
        ?.params,
    ).toEqual({ page: 2, size: 20 });
    await wrapper.get("select").setValue("WAITLISTED");
    await flushPromises();
    expect(
      mock.history.get.filter((r) => r.url?.endsWith("/registrations")).at(-1)
        ?.params,
    ).toEqual({ status: "WAITLISTED", page: 1, size: 20 });
  });
  it("retries errors and displays empty state", async () => {
    mock.onGet("/api/v1/positions/21/registrations").replyOnce(403);
    mock.onGet("/api/v1/positions/21/registrations").reply(200, page([]));
    const wrapper = await open();
    expect(wrapper.get('[role="alert"]').text()).toContain("权限");
    await wrapper.get('[data-test="refresh"]').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("暂无报名");
  });
});
