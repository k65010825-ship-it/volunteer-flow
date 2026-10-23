import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "../api/http";
import { useAuthStore } from "../stores/auth";
import AdminOrganizationsView from "./AdminOrganizationsView.vue";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("locally filters the complete administrator list without inventing server pagination", async () => {
  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore().user = {
    id: "9",
    platformRole: "PLATFORM_ADMIN",
  } as ReturnType<typeof useAuthStore>["user"];
  mock.onGet("/api/v1/organizations").reply(200, {
    data: [
      { id: "1", name: "青年志愿者协会", status: "ACTIVE" },
      { id: "2", name: "阅读社", status: "INACTIVE" },
    ],
  });
  const w = mount(AdminOrganizationsView, { global: { plugins: [pinia] } });
  await flushPromises();
  await w.get('[data-test="organization-filter"]').setValue("阅读");
  expect(w.text()).toContain("阅读社");
  expect(w.text()).not.toContain("青年志愿者协会");
  expect(mock.history.get[0]?.params).toBeUndefined();
  w.unmount();
});
