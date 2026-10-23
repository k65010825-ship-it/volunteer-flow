import { apiClient } from "./http";
import type { OrganizationPage } from "./organizationTypes";
export type AuditValue =
  | null
  | boolean
  | number
  | string
  | AuditValue[]
  | { [key: string]: AuditValue };
export interface AuditLog {
  id: string;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  createTime: string;
  changedFields: string[];
  before: Record<string, AuditValue> | null;
  after: Record<string, AuditValue> | null;
}
export interface AuditFilters {
  page: number;
  size: number;
  actorUserId?: string;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  from?: string;
  to?: string;
}
export async function listAuditLogs(
  id: string,
  filters: AuditFilters,
): Promise<OrganizationPage<AuditLog>> {
  return (
    await apiClient.get(
      `/api/v1/organizations/${encodeURIComponent(id)}/audit-logs`,
      { params: filters },
    )
  ).data;
}
