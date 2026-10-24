import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { expect, it, vi } from "vitest";
import RegisterView from "./RegisterView.vue";
import MockAdapter from "axios-mock-adapter";
import { apiClient } from "../api/http";
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
it("explains the UTF-8 password bound before sending a registration request", async () => {
  const mock = new MockAdapter(apiClient);
  const wrapper = mount(RegisterView, {
    global: { plugins: [createPinia()], stubs: { RouterLink: true } },
  });
  await wrapper.get('input[type="password"]').setValue("密".repeat(25));
  await wrapper.get("form").trigger("submit");
  expect(wrapper.get(".error").text()).toContain("72 字节");
  expect(wrapper.get("button").attributes("disabled")).toBeUndefined();
  expect(mock.history.post).toHaveLength(0);
  wrapper.unmount();
  mock.restore();
});
