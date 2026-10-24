import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { apiClient } from "../api/http";
import AppShell from "./AppShell.vue";
import { useAuthStore } from "../stores/auth";

const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
const setPageVisible = (visible: boolean) =>
  Object.defineProperty(document, "hidden", {
    configurable: true,
    value: !visible,
  });
beforeEach(() => {
  vi.useFakeTimers();
  setActivePinia(createPinia());
  useAuthStore().user = {
    id: "1",
    username: "member",
    realName: "成员",
    studentNumber: "2026001",
    contact: "contact",
    platformRole: "USER",
  };
  setPageVisible(true);
  mock
    .onGet("/api/v1/users/me/notifications/unread-count")
    .reply(200, { data: { unreadCount: 3 } });
  mock.onPost("/api/v1/auth/logout").reply(204);
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  mock.reset();
  setPageVisible(true);
  vi.useRealTimers();
});
async function open(path = "/activities") {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/activities", component: { template: "<p>活动</p>" } },
      { path: "/notifications", component: { template: "<p>通知</p>" } },
      { path: "/registrations", component: { template: "<p>报名</p>" } },
      {
        path: "/login",
        component: { template: "<p>登录</p>" },
        meta: { public: true },
      },
      {
        path: "/admin/organizations",
        component: { template: "<p>组织管理</p>" },
      },
      { path: "/profile", component: { template: "<p>个人资料</p>" } },
    ],
  });
  await router.push(path);
  const wrapper = mount(AppShell, { global: { plugins: [router] } });
  wrappers.push(wrapper);
  await flushPromises();
  return { wrapper, router };
}
describe("AppShell notification badge and polling", () => {
  it("does not poll before account restoration and starts after authentication", async () => {
    const auth = useAuthStore();
    const user = auth.user;
    auth.user = null;
    await open("/profile");
    expect(mock.history.get).toHaveLength(0);
    auth.user = user;
    await flushPromises();
    expect(mock.history.get).toHaveLength(1);
  });
  it("provides an administrator sidebar without volunteer bottom navigation", async () => {
    useAuthStore().user = {
      id: "1",
      username: "admin",
      realName: "管理员",
      studentNumber: "admin1",
      contact: "contact",
      platformRole: "PLATFORM_ADMIN",
    };
    const { wrapper } = await open("/admin/organizations");
    expect(wrapper.find(".admin-sidebar").exists()).toBe(true);
    expect(wrapper.find(".bottom-nav").exists()).toBe(false);
    expect(wrapper.text()).toContain("组织管理");
  });
  it("links the user profile instead of an inert placeholder", async () => {
    const { wrapper } = await open();
    expect(wrapper.get('a[href="/profile"]').text()).toContain("我的资料");
    expect(wrapper.find(".admin-sidebar").exists()).toBe(false);
  });
  it("hides the shared back action on the user home", async () => {
    const { wrapper } = await open();
    expect(wrapper.find(".page-back-button").exists()).toBe(false);
  });
  it("offers a safe home fallback when a subpage was opened directly", async () => {
    const { wrapper, router } = await open("/profile");
    expect(wrapper.get(".page-back-button").text()).toBe("返回上一页");
    await wrapper.get(".page-back-button").trigger("click");
    await flushPromises();
    expect(router.currentRoute.value.fullPath).toBe("/activities?userView=1");
    expect(wrapper.find(".page-back-button").exists()).toBe(false);
  });
  it("renders the unread count on the notification navigation entry", async () => {
    const { wrapper } = await open();
    expect(wrapper.get('[data-test="notification-badge"]').text()).toBe("3");
    expect(wrapper.get('a[href="/notifications"]').text()).toContain("通知");
  });
  it("does not poll while hidden, refreshes immediately when visible, and cleans up on unmount", async () => {
    setPageVisible(false);
    const { wrapper } = await open();
    expect(mock.history.get).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(mock.history.get).toHaveLength(0);
    setPageVisible(true);
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(mock.history.get).toHaveLength(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(60_000);
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(mock.history.get).toHaveLength(1);
  });
  it("stops the timer and visibility listener after logout", async () => {
    const { wrapper, router } = await open();
    expect(mock.history.get).toHaveLength(1);
    await wrapper.get(".logout-button").trigger("click");
    await flushPromises();
    await vi.advanceTimersByTimeAsync(60_000);
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(router.currentRoute.value.path).toBe("/login");
    expect(mock.history.get).toHaveLength(1);
  });
});
