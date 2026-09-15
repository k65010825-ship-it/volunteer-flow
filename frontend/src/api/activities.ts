import { apiClient } from "./http";
import type { ActivityDetail, ActivitySummary, EntityId } from "./types";
export async function listActivities(
  orgId: EntityId,
): Promise<ActivitySummary[]> {
  return (await apiClient.get(`/api/v1/organizations/${orgId}/activities`)).data
    .data;
}
export async function getActivity(id: EntityId): Promise<ActivityDetail> {
  return (await apiClient.get(`/api/v1/activities/${id}`)).data.data;
}
