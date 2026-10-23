import { flushPromises, mount } from "@vue/test-utils";
import MockAdapter from "axios-mock-adapter";
import { afterEach, expect, it } from "vitest";
import { apiClient } from "../api/http";
import OrganizationManagementView from "./OrganizationManagementView.vue";
const mock = new MockAdapter(apiClient);
const wrappers: ReturnType<typeof mount>[] = [];
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
  mock.reset();
});
async function open(codes: string[]) {
  mock.onGet("/api/v1/organizations/1/access").reply(200, {
    data: {
      organizationId: "1",
      memberId: "2",
      roleId: "3",
      roleName: "成员",
      permissionCodes: codes,
    },
  });
  const w = mount(OrganizationManagementView, {
    props: { organizationId: "1" },
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}
it("does not request management data without matching access permissions", async () => {
  const w = await open([]);
  expect(w.text()).toContain("没有成员、角色或邀请管理权限");
  expect(mock.history.get.map((r) => r.url)).toEqual([
    "/api/v1/organizations/1/access",
  ]);
  expect(w.find('[data-test="invite-create"]').exists()).toBe(false);
});
it("protects owner roles and does not clear permissions when loading existing grants fails", async () => {
  mock.onGet("/api/v1/organizations/1/roles").reply(200, {
    data: [
      {
        id: "3",
        name: "OWNER",
        protectedRole: true,
        builtInType: "OWNER",
        status: "ACTIVE",
      },
      {
        id: "4",
        name: "Custom",
        protectedRole: false,
        builtInType: "CUSTOM",
        status: "ACTIVE",
      },
    ],
  });
  mock
    .onGet("/api/v1/organizations/1/permissions")
    .reply(200, { data: [{ code: "member:read", name: "查看成员" }] });
  mock.onGet("/api/v1/organizations/1/roles/4/permissions").reply(500);
  const w = await open(["role:read", "role:update"]);
  expect(w.find('[data-test="role-edit-3"]').exists()).toBe(false);
  await w.get('[data-test="role-edit-4"]').trigger("click");
  await flushPromises();
  expect(
    w.get<HTMLButtonElement>('[data-test="role-save"]').element.disabled,
  ).toBe(true);
  expect(mock.history.put).toHaveLength(0);
});

function deferredReply() {
  let resolve!: (reply: [number, unknown]) => void;
  const promise = new Promise<[number, unknown]>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function mockOrganizationTwo(codes: string[]) {
  mock.onGet("/api/v1/organizations/2/access").reply(200, {
    data: {
      organizationId: "2",
      memberId: "22",
      roleId: "23",
      roleName: "B成员",
      permissionCodes: codes,
    },
  });
}

it("discards an old organization's one-time invite response after navigation", async () => {
  const response = deferredReply();
  mock
    .onPost("/api/v1/organizations/1/invitations")
    .reply(() => response.promise);
  mockOrganizationTwo(["member:invite"]);
  const w = await open(["member:invite"]);
  await w.get("form.invite-form").trigger("submit");
  await flushPromises();
  await w.setProps({ organizationId: "2" });
  await flushPromises();
  response.resolve([
    201,
    {
      data: {
        inviteId: "99",
        code: "A-PRIVATE-CODE",
        expiresAt: "2030-01-01T00:00:00Z",
        maxUses: 50,
      },
    },
  ]);
  await flushPromises();
  expect(w.text()).not.toContain("A-PRIVATE-CODE");
  expect(w.text()).not.toContain("邀请码已生成");
  expect(
    w.get<HTMLButtonElement>('[data-test="invite-create"]').element.disabled,
  ).toBe(false);
});

it("does not continue role grant replacement in a newly selected organization", async () => {
  const response = deferredReply();
  mock
    .onGet("/api/v1/organizations/1/roles")
    .reply(200, {
      data: [
        {
          id: "4",
          name: "Custom",
          protectedRole: false,
          builtInType: "CUSTOM",
          status: "ACTIVE",
        },
      ],
    });
  mock
    .onGet("/api/v1/organizations/1/permissions")
    .reply(200, { data: [{ code: "member:read", name: "查看成员" }] });
  mock
    .onGet("/api/v1/organizations/1/roles/4/permissions")
    .reply(200, { data: ["member:read"] });
  mock.onPut("/api/v1/organizations/1/roles/4").reply(() => response.promise);
  mockOrganizationTwo([]);
  const w = await open(["role:read", "role:update"]);
  await w.get('[data-test="role-edit-4"]').trigger("click");
  await flushPromises();
  await w.get("form.role-form").trigger("submit");
  await flushPromises();
  await w.setProps({ organizationId: "2" });
  await flushPromises();
  response.resolve([200, { data: { id: "4", name: "Custom" } }]);
  await flushPromises();
  expect(mock.history.put.map((r) => r.url)).toEqual([
    "/api/v1/organizations/1/roles/4",
  ]);
  expect(
    mock.history.get.some((r) => r.url === "/api/v1/organizations/2/roles"),
  ).toBe(false);
  expect(w.text()).not.toContain("角色已保存");
});

it("does not reload B members or publish A assignment success after navigation", async () => {
  const response = deferredReply();
  mock
    .onGet("/api/v1/organizations/1/members")
    .reply(200, {
      data: [{ id: "10", userId: "11", roleId: "4", status: "ACTIVE" }],
      meta: { page: 1, size: 20, total: 1 },
    });
  mock.onGet("/api/v1/organizations/1/roles").reply(200, {
    data: [
      { id: "4", name: "Current", status: "ACTIVE" },
      { id: "5", name: "New", status: "ACTIVE" },
    ],
  });
  mock.onGet("/api/v1/organizations/1/permissions").reply(200, { data: [] });
  mock
    .onPatch("/api/v1/organizations/1/members/10/role")
    .reply(() => response.promise);
  mockOrganizationTwo([]);
  const w = await open(["member:read", "member:role_assign", "role:read"]);
  await w.get('select[aria-label="用户 11 的角色"]').setValue("5");
  await w.get("tbody .actions button").trigger("click");
  await flushPromises();
  await w.setProps({ organizationId: "2" });
  await flushPromises();
  response.resolve([200, { data: { id: "10", roleId: "5" } }]);
  await flushPromises();
  expect(JSON.parse(mock.history.patch[0]!.data)).toEqual({ roleId: "5" });
  expect(
    mock.history.get.some((r) => r.url === "/api/v1/organizations/2/members"),
  ).toBe(false);
  expect(w.text()).not.toContain("成员角色已更新");
});
