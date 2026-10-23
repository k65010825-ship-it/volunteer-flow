import { apiClient } from "./http";
import type { Organization } from "./types";
import { isAxiosError } from "axios";
import type {
  OrganizationAccess,
  OrganizationMember,
  OrganizationRole,
  OrganizationPermission,
  OrganizationPage,
  Invitation,
  CreateOrganizationInput,
  RoleInput,
  CreateRoleInput,
  CreateInvitationInput,
} from "./organizationTypes";
export type { OrganizationAccess } from "./organizationTypes";
export async function listOrganizations(): Promise<Organization[]> {
  return (await apiClient.get("/api/v1/organizations")).data.data;
}
export async function joinOrganization(code: string) {
  return (await apiClient.post("/api/v1/organization-memberships", { code }))
    .data.data;
}
const orgPath = (id: string) =>
  `/api/v1/organizations/${encodeURIComponent(id)}`;
export async function getOrganizationAccess(
  id: string,
): Promise<OrganizationAccess> {
  return (await apiClient.get(`${orgPath(id)}/access`)).data.data;
}
export async function createOrganization(
  input: CreateOrganizationInput,
): Promise<Organization> {
  return (await apiClient.post("/api/v1/organizations", input)).data.data;
}
export async function deactivateOrganization(id: string): Promise<void> {
  await apiClient.post(`${orgPath(id)}/deactivation`);
}
export async function listOrganizationMembers(
  id: string,
  page = 1,
): Promise<OrganizationPage<OrganizationMember>> {
  return (
    await apiClient.get(`${orgPath(id)}/members`, {
      params: { page, size: 20 },
    })
  ).data;
}
export async function listOrganizationRoles(
  id: string,
): Promise<OrganizationRole[]> {
  return (await apiClient.get(`${orgPath(id)}/roles`)).data.data;
}
export async function listOrganizationPermissions(
  id: string,
): Promise<OrganizationPermission[]> {
  return (await apiClient.get(`${orgPath(id)}/permissions`)).data.data;
}
export async function getRolePermissions(
  id: string,
  roleId: string,
): Promise<string[]> {
  return (
    await apiClient.get(
      `${orgPath(id)}/roles/${encodeURIComponent(roleId)}/permissions`,
    )
  ).data.data;
}
export async function createOrganizationRole(
  id: string,
  input: CreateRoleInput,
): Promise<OrganizationRole> {
  return (await apiClient.post(`${orgPath(id)}/roles`, input)).data.data;
}
export async function updateOrganizationRole(
  id: string,
  roleId: string,
  input: RoleInput,
): Promise<OrganizationRole> {
  return (
    await apiClient.put(
      `${orgPath(id)}/roles/${encodeURIComponent(roleId)}`,
      input,
    )
  ).data.data;
}
export async function setRolePermissions(
  id: string,
  roleId: string,
  permissionCodes: string[],
): Promise<void> {
  await apiClient.put(
    `${orgPath(id)}/roles/${encodeURIComponent(roleId)}/permissions`,
    { permissionCodes },
  );
}
export async function deactivateOrganizationRole(
  id: string,
  roleId: string,
): Promise<void> {
  await apiClient.post(
    `${orgPath(id)}/roles/${encodeURIComponent(roleId)}/deactivation`,
  );
}
export async function deleteOrganizationRole(
  id: string,
  roleId: string,
): Promise<void> {
  await apiClient.delete(`${orgPath(id)}/roles/${encodeURIComponent(roleId)}`);
}
export async function assignMemberRole(
  id: string,
  memberId: string,
  roleId: string,
): Promise<OrganizationMember> {
  return (
    await apiClient.patch(
      `${orgPath(id)}/members/${encodeURIComponent(memberId)}/role`,
      { roleId },
    )
  ).data.data;
}
export async function createInvitation(
  id: string,
  input: CreateInvitationInput,
): Promise<Invitation> {
  return (await apiClient.post(`${orgPath(id)}/invitations`, input)).data.data;
}
export async function deactivateInvitation(
  id: string,
  inviteId: string,
): Promise<void> {
  await apiClient.post(
    `${orgPath(id)}/invitations/${encodeURIComponent(inviteId)}/deactivation`,
  );
}
export function organizationErrorMessage(error: unknown): string {
  if (!isAxiosError<{ code?: string }>(error)) return "操作失败，请稍后重试。";
  const messages: Record<string, string> = {
    PROTECTED_ROLE: "负责人角色受保护，不能修改或删除。",
    LAST_OWNER_REQUIRED: "组织必须保留至少一位有效负责人。",
    ROLE_IN_USE: "该角色仍被成员使用，请先调整成员角色。",
    OWNER_NOT_FOUND: "负责人用户不存在或已停用，请核对用户 ID。",
    INVALID_PERMISSION_CODES: "权限已变更，请重新加载后再试。",
  };
  const code = error.response?.data?.code;
  if (code && messages[code]) return messages[code];
  if (error.response?.status === 403)
    return "你没有执行此操作的权限，请联系组织负责人。";
  if (error.response?.status === 404)
    return "组织或记录不存在、已停用，或你无权访问。";
  if (error.response?.status === 409)
    return "数据状态已变更或存在冲突，请刷新后重试。";
  if (error.response?.status === 400 || error.response?.status === 422)
    return "提交内容不符合要求，请检查表单。";
  return "操作失败，请检查网络并重试。";
}
