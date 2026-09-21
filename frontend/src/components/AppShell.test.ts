import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { apiClient } from "../api/http";
import AppShell from "./AppShell.vue";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const setPageVisible = (visible: boolean) => Object.defineProperty(document, "hidden", { configurable: true, value: !visible });
beforeEach(() => {
  vi.useFakeTimers(); setActivePinia(createPinia()); setPageVisible(true);
  mock.onGet("/api/v1/users/me/notifications/unread-count").reply(200, { data: { unreadCount: 3 } });
  mock.onPost("/api/v1/auth/logout").reply(204);
});
afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); mock.reset(); setPageVisible(true); vi.useRealTimers(); });
async function open(path = "/activities") {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: "/activities", component: { template: "<p>活动</p>" } },
    { path: "/notifications", component: { template: "<p>通知</p>" } },
    { path: "/login", component: { template: "<p>登录</p>" }, meta: { public: true } },
  ] });
  await router.push(path);
  const wrapper = mount(AppShell, { global: { plugins: [router] } }); wrappers.push(wrapper);
  await flushPromises(); return { wrapper, router };
}
describe("AppShell notification badge and polling", () => {
  it("renders the unread count on the notification navigation entry", async () => {
    const { wrapper } = await open();
    expect(wrapper.get('[data-test="notification-badge"]').text()).toBe("3");
    expect(wrapper.get('a[href="/notifications"]').text()).toContain("通知");
  });
  it("does not poll while hidden, refreshes immediately when visible, and cleans up on unmount", async () => {
    setPageVisible(false); const { wrapper } = await open();
    expect(mock.history.get).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(60_000); expect(mock.history.get).toHaveLength(0);
    setPageVisible(true); document.dispatchEvent(new Event("visibilitychange")); await flushPromises(); expect(mock.history.get).toHaveLength(1);
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(60_000); document.dispatchEvent(new Event("visibilitychange")); await flushPromises();
    expect(mock.history.get).toHaveLength(1);
  });
  it("stops the timer and visibility listener after logout", async () => {
    const { wrapper, router } = await open(); expect(mock.history.get).toHaveLength(1);
    await wrapper.get(".logout-button").trigger("click"); await flushPromises();
    await vi.advanceTimersByTimeAsync(60_000); document.dispatchEvent(new Event("visibilitychange")); await flushPromises();
    expect(router.currentRoute.value.path).toBe("/login"); expect(mock.history.get).toHaveLength(1);
  });
});
