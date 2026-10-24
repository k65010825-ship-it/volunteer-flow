import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { apiClient, getAccessToken, setAccessToken } from "../api/http";
import { useAuthStore } from "../stores/auth";
import ProfileView from "./ProfileView.vue";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("vue-router", () => ({ useRouter: () => ({ replace }) }));
const mock = new MockAdapter(apiClient);
function setup() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore();
  auth.user = {
    id: "1",
    username: "student",
    realName: "原姓名",
    studentNumber: "2026001",
    contact: "old@example.com",
    platformRole: "USER",
  };
  auth.ready = true;
  return {
    auth,
    wrapper: mount(ProfileView, { global: { plugins: [pinia] } }),
  };
}
beforeEach(() => {
  replace.mockReset();
  setAccessToken("old-token");
});
afterEach(() => mock.reset());
it("saves only editable profile fields and updates the shared account state", async () => {
  const { auth, wrapper } = setup();
  mock
    .onPut("/api/v1/auth/me")
    .reply(200, {
      data: { ...auth.user, realName: "新姓名", contact: "new@example.com" },
    });
  await wrapper.get('[name="realName"]').setValue(" 新姓名 ");
  await wrapper.get('[name="contact"]').setValue("new@example.com");
  await wrapper.get('[data-test="profile-form"]').trigger("submit");
  await flushPromises();
  expect(JSON.parse(mock.history.put[0]!.data)).toEqual({
    realName: "新姓名",
    contact: "new@example.com",
  });
  expect(auth.user?.realName).toBe("新姓名");
  expect(wrapper.text()).toContain("资料已保存");
  expect(wrapper.find('[name="username"]').exists()).toBe(false);
  wrapper.unmount();
});
async function fillPassword(
  wrapper: ReturnType<typeof setup>["wrapper"],
  password = "new-password-123",
) {
  await wrapper.get('[name="currentPassword"]').setValue("old-password-123");
  await wrapper.get('[name="newPassword"]').setValue(password);
  await wrapper.get('[name="confirmPassword"]').setValue(password);
}
it("clears local credentials and returns to login after changing the password", async () => {
  const { auth, wrapper } = setup();
  mock.onPost("/api/v1/auth/password").reply(204);
  await fillPassword(wrapper);
  await wrapper.get('[data-test="password-form"]').trigger("submit");
  await flushPromises();
  expect(JSON.parse(mock.history.post[0]!.data)).toEqual({
    currentPassword: "old-password-123",
    newPassword: "new-password-123",
  });
  expect(auth.user).toBeNull();
  expect(getAccessToken()).toBeFalsy();
  expect(replace).toHaveBeenCalledWith({
    path: "/login",
    query: { passwordChanged: "1" },
  });
  wrapper.unmount();
});
it("rejects mismatched confirmation without making a request", async () => {
  const { wrapper } = setup();
  await fillPassword(wrapper);
  await wrapper.get('[name="confirmPassword"]').setValue("different");
  await wrapper.get('[data-test="password-form"]').trigger("submit");
  expect(mock.history.post).toHaveLength(0);
  expect(wrapper.text()).toContain("两次输入的新密码不一致");
  wrapper.unmount();
});
it("enforces BCrypt's UTF-8 byte limit before submitting", async () => {
  const { wrapper } = setup();
  await fillPassword(wrapper, "密".repeat(25));
  await wrapper.get('[data-test="password-form"]').trigger("submit");
  expect(mock.history.post).toHaveLength(0);
  expect(wrapper.text()).toContain("72 字节");
  wrapper.unmount();
});
it("keeps the session on wrong current password and clears sensitive form values", async () => {
  const { auth, wrapper } = setup();
  mock
    .onPost("/api/v1/auth/password")
    .reply(400, {
      code: "INVALID_CURRENT_PASSWORD",
      message: "当前密码不正确",
    });
  await fillPassword(wrapper);
  await wrapper.get('[data-test="password-form"]').trigger("submit");
  await flushPromises();
  expect(wrapper.text()).toContain("当前密码不正确");
  expect(auth.user).not.toBeNull();
  expect(replace).not.toHaveBeenCalled();
  expect(
    (wrapper.get('[name="currentPassword"]').element as HTMLInputElement).value,
  ).toBe("");
  wrapper.unmount();
});
