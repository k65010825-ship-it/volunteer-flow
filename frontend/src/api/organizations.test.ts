import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "./http";
import {
  createInvitation,
  getRolePermissions,
  listOrganizationMembers,
  organizationErrorMessage,
  setRolePermissions,
} from "./organizations";
import { listAuditLogs } from "./audit";
const mock = new MockAdapter(apiClient);
afterEach(() => mock.reset());
it("preserves the top-level members pagination envelope", async () => {
  const page = {
    data: [{ id: "12", userId: "9007199254740993" }],
    meta: { page: 2, size: 20, total: 21 },
  };
  mock
    .onGet("/api/v1/organizations/1/members", { params: { page: 2, size: 20 } })
    .reply(200, page);
  expect(await listOrganizationMembers("1", 2)).toEqual(page);
});
it("uses only actual invite fields and preserves the one-time code", async () => {
  mock.onPost("/api/v1/organizations/1/invitations").reply((config) => {
    expect(JSON.parse(config.data)).toEqual({
      maxUses: 5,
      expiresAt: "2030-01-01T00:00:00Z",
    });
    return [
      201,
      { data: { code: "aBc", expiresAt: "2030-01-01T00:00:00Z", maxUses: 5 } },
    ];
  });
  expect(
    (
      await createInvitation("1", {
        maxUses: 5,
        expiresAt: "2030-01-01T00:00:00Z",
      })
    ).code,
  ).toBe("aBc");
});
it("loads existing permission codes before allowing exact replacement", async () => {
  mock
    .onGet("/api/v1/organizations/1/roles/2/permissions")
    .reply(200, { data: ["member:read"] });
  mock
    .onPut("/api/v1/organizations/1/roles/2/permissions", {
      permissionCodes: ["member:read"],
    })
    .reply(204);
  await setRolePermissions("1", "2", await getRolePermissions("1", "2"));
  expect(mock.history.put).toHaveLength(1);
});
it("preserves audit snapshots and forwards supported filters", async () => {
  const page = {
    data: [
      {
        id: "3",
        before: { name: "<script>" },
        after: { name: "new" },
        changedFields: ["name"],
      },
    ],
    meta: { page: 1, size: 20, total: 1 },
  };
  mock
    .onGet("/api/v1/organizations/1/audit-logs", {
      params: { page: 1, size: 20, action: "role.updated" },
    })
    .reply(200, page);
  expect(
    await listAuditLogs("1", { page: 1, size: 20, action: "role.updated" }),
  ).toEqual(page);
});
it("safely handles bodyless permission errors", () => {
  expect(
    organizationErrorMessage({ isAxiosError: true, response: { status: 403 } }),
  ).toContain("权限");
});
