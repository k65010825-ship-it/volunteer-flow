import type { EntityId } from "./types";
export interface OrganizationAccess {
  organizationId: EntityId;
  memberId: EntityId;
  roleId: EntityId;
  roleName: string;
  permissionCodes: string[];
}
export interface OrganizationMember {
  id: EntityId;
  organizationId: EntityId;
  userId: EntityId;
  roleId: EntityId;
  status: string;
  joinedAt: string;
}
export interface OrganizationRole {
  id: EntityId;
  organizationId: EntityId;
  name: string;
  description?: string;
  builtInType: string;
  protectedRole: boolean;
  status: string;
}
export interface OrganizationPermission {
  id: EntityId;
  code: string;
  name: string;
  description?: string;
  status: string;
}
export interface OrganizationPage<T> {
  data: T[];
  meta: {
    total: number | string;
    page: number | string;
    size: number | string;
  };
}
export interface Invitation {
  inviteId?: EntityId;
  code: string;
  expiresAt: string;
  maxUses: number;
}
export interface CreateOrganizationInput {
  name: string;
  description: string;
  ownerUserId: EntityId;
}
export interface RoleInput {
  name: string;
  description: string;
}
export interface CreateRoleInput extends RoleInput {
  permissionCodes: string[];
}
export interface CreateInvitationInput {
  maxUses?: number;
  expiresAt?: string;
}
