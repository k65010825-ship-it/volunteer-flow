import { apiClient } from "./http";
import type { Organization } from "./types";
export async function listOrganizations(): Promise<Organization[]> {
  return (await apiClient.get("/api/v1/organizations")).data.data;
}
export async function joinOrganization(code: string) {
  return (await apiClient.post("/api/v1/organization-memberships", { code }))
    .data.data;
}
