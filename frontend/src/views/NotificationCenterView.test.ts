import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { apiClient } from "../api/http";
import NotificationCenterView from "./NotificationCenterView.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const first = { id: "1", type: "REGISTRATION_RESULT", title: "报名结果", content: "你已被录取", businessType: "REGISTRATION", businessId: "31", readAt: null, createTime: "2026-09-20T10:00:00" };

beforeEach(() => {
  setActivePinia(createPinia());
  mock.onGet("/api/v1/users/me/notifications/unread-count").reply(200, { data: { unreadCount: 1 } });
  mock.onGet("/api/v1/users/me/notifications").reply((config) => config.params?.cursor === "next-page"
    ? [200, { data: { items: [{ id: "2", type: "ACTIVITY_CHANGE", title: "活动信息变更", content: "集合地点已更新", businessType: "ACTIVITY", businessId: "11", readAt: "2026-09-20T10:01:00", createTime: "2026-09-20T09:00:00" }], nextCursor: null, hasMore: false } }]
    : [200, { data: { items: [first], nextCursor: "next-page", hasMore: true } }]);
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); mock.reset(); vi.useRealTimers(); });

async function open() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/notifications", component: NotificationCenterView },
    { path: "/registrations/:id", component: { template: "<p>报名</p>" } },
    { path: "/activities/:id", component: { template: "<p>活动</p>" } },
  ] });
  await router.push("/notifications");
  const wrapper = mount(NotificationCenterView, { attachTo: document.body, global: { plugins: [router] } });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}

describe("NotificationCenterView", () => {
  it("loads the next cursor page without replacing already loaded notifications", async () => {
    const { wrapper } = await open();
    await wrapper.get('[data-test="load-more"]').trigger("click");
    await flushPromises();
    expect(mock.history.get.at(-1)?.params).toEqual({ cursor: "next-page", size: 20 });
    expect(wrapper.findAll('[data-test="notification-item"]')).toHaveLength(2);
  });
  it("waits for marking an unread registration notification read before navigating", async () => {
    let finishRead!: () => void;
    mock.onPost("/api/v1/notifications/1/read").reply(() => new Promise((resolve) => { finishRead = () => resolve([204]); }));
    const { wrapper, router } = await open();
    void wrapper.get('[data-test="notification-item"]').trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/notifications");
    finishRead();
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/registrations/31");
  });
  it("marks all loaded notifications read", async () => {
    mock.onPost("/api/v1/users/me/notifications/read-all").reply(204);
    mock.onGet("/api/v1/users/me/notifications/unread-count").reply(200, { data: { unreadCount: 0 } });
    const { wrapper } = await open();
    await wrapper.get('[data-test="read-all"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-test="notification-item"]').classes()).toContain("notification-read");
  });
  it("keeps cursor pages read after a successful read-all instead of marking them again", async () => {
    mock.onPost("/api/v1/users/me/notifications/read-all").reply(204);
    mock.onGet("/api/v1/users/me/notifications/unread-count").reply(200, { data: { unreadCount: 0 } });
    mock.onGet("/api/v1/users/me/notifications").reply((config) => config.params?.cursor === "next-page"
      ? [200, { data: { items: [{ id: "2", type: "ACTIVITY_CHANGE", title: "活动信息变更", content: "集合地点已更新", businessType: "ACTIVITY", businessId: "11", readAt: null, createTime: "2026-09-20T09:00:00" }], nextCursor: null, hasMore: false } }]
      : [200, { data: { items: [first], nextCursor: "next-page", hasMore: true } }]);
    mock.onPost("/api/v1/notifications/2/read").reply(204);
    const { wrapper } = await open();

    await wrapper.get('[data-test="read-all"]').trigger("click");
    await wrapper.get('[data-test="load-more"]').trigger("click");
    await flushPromises();
    await wrapper.findAll('[data-test="notification-item"]')[1].trigger("click");
    await flushPromises();

    expect(wrapper.findAll(".unread-dot")).toHaveLength(0);
    expect(mock.history.post.some((request) => request.url?.endsWith("/2/read"))).toBe(false);
  });
  it("opens the existing registrations experience for a promotion offer without treating offer id as cycle id", async () => {
    mock.onGet("/api/v1/users/me/notifications").reply(200, { data: { items: [{ ...first, businessType: "PROMOTION_OFFER", businessId: "99" }], nextCursor: null, hasMore: false } });
    mock.onPost("/api/v1/notifications/1/read").reply(204);
    const { wrapper, router } = await open();

    await wrapper.get('[data-test="notification-item"]').trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/registrations");
  });
  it("does not navigate when marking a notification read is rejected", async () => {
    mock.onPost("/api/v1/notifications/1/read").reply(500);
    const { wrapper, router } = await open();

    await wrapper.get('[data-test="notification-item"]').trigger("click");
    await flushPromises();

    expect(router.currentRoute.value.path).toBe("/notifications");
    expect(wrapper.get('[role="alert"]').text()).toContain("标记通知已读失败");
  });
  it("keeps unknown business types readable without navigating", async () => {
    mock.onGet("/api/v1/users/me/notifications").reply(200, { data: { items: [{ ...first, businessType: "FUTURE_TYPE", businessId: "99" }], nextCursor: null, hasMore: false } });
    mock.onPost("/api/v1/notifications/1/read").reply(204);
    const { wrapper, router } = await open();
    await wrapper.get('[data-test="notification-item"]').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("你已被录取");
    expect(router.currentRoute.value.path).toBe("/notifications");
  });
});
