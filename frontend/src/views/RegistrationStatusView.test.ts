import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "../api/http";
import { activityDetail, registration } from "../test/registrationFixtures";
import RegistrationStatusView from "./RegistrationStatusView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
beforeEach(() => {
  mock.onGet("/api/v1/activities/10").reply(200, { data: activityDetail });
  mock.onGet("/api/v1/registrations/30").reply(200, { data: registration() });
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
  vi.useRealTimers();
});
async function open() {
  const wrapper = mount(RegistrationStatusView, {
    props: { id: "30" },
    global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}
const offer = {
  id: "50",
  status: "PENDING" as const,
  expiresAt: "2026-09-18T10:01:00",
  reason: "候补递补",
};

describe("RegistrationStatusView", () => {
  it("does not let an old action refresh unlock a new route's pending action", async () => {
    mock.onGet("/api/v1/registrations/30").reply(200, {
      data: registration({ pendingOffer: offer }),
    });
    const wrapper = await open();
    let finishOldRefresh!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/registrations/30").reply(
      () =>
        new Promise((resolve) => {
          finishOldRefresh = resolve;
        }),
    );
    mock.onPost("/api/v1/promotion-offers/50/responses").reply(200, {
      data: { ...offer, status: "ACCEPTED" },
    });
    await wrapper.get('[data-test="accept"]').trigger("click");
    await flushPromises();
    mock.onGet("/api/v1/registrations/31").reply(200, {
      data: registration({
        registrationId: "31",
        pendingOffer: { ...offer, id: "51" },
      }),
    });
    let finishNewAction!: (value: [number, unknown]) => void;
    mock.onPost("/api/v1/promotion-offers/51/responses").reply(
      () =>
        new Promise((resolve) => {
          finishNewAction = resolve;
        }),
    );
    await wrapper.setProps({ id: "31" });
    await flushPromises();
    await wrapper.get('[data-test="accept"]').trigger("click");
    finishOldRefresh([200, { data: registration({ status: "CONFIRMED" }) }]);
    await flushPromises();
    expect(
      wrapper.get('[data-test="accept"]').attributes("disabled"),
    ).toBeDefined();
    finishNewAction([
      200,
      { data: { ...offer, id: "51", status: "ACCEPTED" } },
    ]);
    await flushPromises();
  });

  it("ignores an old route's delayed registration response", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock.onGet("/api/v1/registrations/30").reply(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    mock.onGet("/api/v1/registrations/31").reply(200, {
      data: registration({ registrationId: "31", status: "REJECTED" }),
    });
    const wrapper = await open();
    await wrapper.setProps({ id: "31" });
    await flushPromises();
    finish([200, { data: registration({ status: "CONFIRMED" }) }]);
    await flushPromises();
    expect(wrapper.text()).toContain("未通过审核");
    expect(wrapper.text()).not.toContain("已录取");
  });

  it("blocks repeated responses, displays a conflict, and refreshes server truth", async () => {
    let finish!: (value: [number, unknown]) => void;
    mock
      .onGet("/api/v1/registrations/30")
      .reply(200, { data: registration({ pendingOffer: offer }) });
    mock.onPost("/api/v1/promotion-offers/50/responses").reply(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const wrapper = await open();
    await wrapper.get('[data-test="accept"]').trigger("click");
    await wrapper.get('[data-test="decline"]').trigger("click");
    expect(mock.history.post).toHaveLength(1);
    mock
      .onGet("/api/v1/registrations/30")
      .reply(200, { data: registration({ status: "PROMOTION_EXPIRED" }) });
    finish([409, { code: "PROMOTION_OFFER_EXPIRED" }]);
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("状态已变化");
    expect(wrapper.text()).toContain("递补已过期");
    expect(wrapper.find('[data-test="accept"]').exists()).toBe(false);
  });

  it("keeps actions when enrichment fails and does not request a reason on unrelated errors", async () => {
    mock.onGet("/api/v1/activities/10").reply(404);
    mock.onPost("/api/v1/registrations/30/cancellation").reply(503);
    const wrapper = await open();
    expect(wrapper.text()).toContain("岗位 #20");
    await wrapper.get('[data-test="cancel"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="cancellation-reason"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.get('[data-test="cancel"]').attributes("disabled"),
    ).toBeUndefined();
    expect(wrapper.get('[role="alert"]').text()).toContain("操作失败");
  });

  it("shows sequenced waitlist rank but not for an unordered review candidate", async () => {
    const wrapper = await open();
    expect(wrapper.text()).toContain("当前位次：2");
    expect(wrapper.text()).toContain("原始序号：5");
    mock.onGet("/api/v1/registrations/30").reply(200, {
      data: registration({
        positionId: "21",
        waitlistSequence: null,
        currentWaitlistPosition: null,
        waitlistCount: null,
      }),
    });
    await wrapper.get('[data-test="refresh"]').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("无序候选池");
    expect(wrapper.text()).not.toContain("当前位次");
  });

  it.each(["ACCEPT", "DECLINE"])(
    "sends %s and refreshes server state",
    async (decision) => {
      mock
        .onGet("/api/v1/registrations/30")
        .reply(200, { data: registration({ pendingOffer: offer }) });
      mock.onPost("/api/v1/promotion-offers/50/responses").reply(200, {
        data: {
          ...offer,
          status: decision === "ACCEPT" ? "ACCEPTED" : "DECLINED",
        },
      });
      const wrapper = await open();
      mock.onGet("/api/v1/registrations/30").reply(200, {
        data: registration({
          status: decision === "ACCEPT" ? "CONFIRMED" : "PROMOTION_DECLINED",
        }),
      });
      await wrapper
        .get(`[data-test="${decision.toLowerCase()}"]`)
        .trigger("click");
      await flushPromises();
      expect(JSON.parse(mock.history.post[0].data)).toEqual({ decision });
      expect(wrapper.text()).toContain(
        decision === "ACCEPT" ? "已录取" : "已拒绝递补",
      );
      expect(wrapper.find('[data-test="accept"]').exists()).toBe(false);
    },
  );

  it("requests a late-cancellation reason only after the stable server error and retries", async () => {
    mock
      .onPost("/api/v1/registrations/30/cancellation")
      .replyOnce(422, { code: "CANCELLATION_REASON_REQUIRED" });
    mock.onPost("/api/v1/registrations/30/cancellation").reply(200, {
      data: { registrationId: "30", cycleId: "40", status: "LATE_CANCELED" },
    });
    const wrapper = await open();
    expect(wrapper.find('[data-test="cancellation-reason"]').exists()).toBe(
      false,
    );
    await wrapper.get('[data-test="cancel"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[0].data)).toEqual({});
    await wrapper.get('[data-test="cancellation-reason"]').setValue("   ");
    await wrapper.get('[data-test="cancel"]').trigger("click");
    expect(mock.history.post).toHaveLength(1);
    await wrapper
      .get('[data-test="cancellation-reason"]')
      .setValue("  课程冲突  ");
    mock
      .onGet("/api/v1/registrations/30")
      .reply(200, { data: registration({ status: "LATE_CANCELED" }) });
    await wrapper.get('[data-test="cancel"]').trigger("click");
    await flushPromises();
    expect(JSON.parse(mock.history.post[1].data)).toEqual({
      reason: "课程冲突",
    });
    expect(wrapper.text()).toContain("迟取消");
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false);
  });

  it("refreshes once at countdown zero without locally expiring or accepting the offer", async () => {
    vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"] });
    vi.setSystemTime(new Date("2026-09-18T10:00:59"));
    mock
      .onGet("/api/v1/registrations/30")
      .reply(200, { data: registration({ pendingOffer: offer }) });
    const wrapper = await open();
    expect(wrapper.text()).toContain("00:01");
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(
      mock.history.get.filter((r) => r.url === "/api/v1/registrations/30"),
    ).toHaveLength(2);
    expect(wrapper.text()).toContain("以后端确认为准");
    expect(wrapper.find('[data-test="accept"]').exists()).toBe(true);
    await vi.advanceTimersByTimeAsync(3000);
    expect(
      mock.history.get.filter((r) => r.url === "/api/v1/registrations/30"),
    ).toHaveLength(2);
    wrapper.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
