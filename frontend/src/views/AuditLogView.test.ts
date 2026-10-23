import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "../api/http";
import AuditLogView from "./AuditLogView.vue";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("does not fetch audit records without audit:read", async () => {
  mock
    .onGet("/api/v1/organizations/1/access")
    .reply(200, { data: { permissionCodes: [] } });
  const w = mount(AuditLogView, { props: { organizationId: "1" } });
  await flushPromises();
  expect(w.text()).toContain("没有审计日志查看权限");
  expect(mock.history.get).toHaveLength(1);
  w.unmount();
});
it("renders snapshot content as text, never HTML", async () => {
  mock
    .onGet("/api/v1/organizations/1/access")
    .reply(200, { data: { permissionCodes: ["audit:read"] } });
  mock.onGet("/api/v1/organizations/1/audit-logs").reply(200, {
    data: [
      {
        id: "5",
        actorUserId: "6",
        action: "role.updated",
        resourceType: "rbac_role",
        resourceId: "7",
        createTime: "2026-10-24T00:00:00",
        changedFields: ["name"],
        before: { name: "<script>alert(1)</script>" },
        after: { name: "new" },
      },
    ],
    meta: { page: 1, size: 20, total: 1 },
  });
  const w = mount(AuditLogView, { props: { organizationId: "1" } });
  await flushPromises();
  expect(w.text()).toContain("<script>alert(1)</script>");
  expect(w.find("script").exists()).toBe(false);
  w.unmount();
});
