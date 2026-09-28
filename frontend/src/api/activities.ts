import { apiClient } from "./http";
import type {
  Activity,
  ActivityDetail,
  ActivitySummary,
  EntityId,
  Position,
} from "./types";
export async function listActivities(
  orgId: EntityId,
): Promise<ActivitySummary[]> {
  return (await apiClient.get(`/api/v1/organizations/${orgId}/activities`)).data
    .data;
}
export async function getActivity(id: EntityId): Promise<ActivityDetail> {
  return (await apiClient.get(`/api/v1/activities/${id}`)).data.data;
}

export interface ActivityChangeRequest {
  title: string;
  description: string;
  location: string;
  registrationEndAt: string;
  freeCancelDeadlineAt?: string | null;
  activityStartAt: string;
  activityEndAt: string;
  reason: string;
}

export interface PositionChangeRequest {
  name: string;
  description: string;
  capacity: number;
  registrationMode: Position["registrationMode"];
  promotionTimeoutMinutes: number;
  serviceStartAt?: string | null;
  serviceEndAt?: string | null;
  meetingLocation?: string | null;
  reason: string;
}

export interface PositionDraftRequest {
  name: string;
  description: string;
  capacity: number;
  registrationMode: Position["registrationMode"];
  promotionTimeoutMinutes: number;
  serviceStartAt?: string | null;
  serviceEndAt?: string | null;
  meetingLocation?: string | null;
}

export interface ActivityChangeResult {
  activity: Activity;
  auditId: EntityId;
  notifiedRecipients: number;
}

export interface PositionChangeResult {
  position: Position;
  auditId: EntityId;
  notifiedRecipients: number;
}

export async function changeActivity(
  id: EntityId,
  body: ActivityChangeRequest,
): Promise<ActivityChangeResult> {
  return (await apiClient.post(`/api/v1/activities/${id}/changes`, body)).data
    .data;
}

export async function changePosition(
  id: EntityId,
  body: PositionChangeRequest,
): Promise<PositionChangeResult> {
  return (await apiClient.post(`/api/v1/positions/${id}/changes`, body)).data
    .data;
}

export async function createPosition(
  activityId: EntityId,
  body: PositionDraftRequest,
): Promise<Position> {
  return (await apiClient.post(`/api/v1/activities/${activityId}/positions`, body))
    .data.data;
}

export async function publishPosition(
  id: EntityId,
): Promise<PositionChangeResult> {
  return (await apiClient.post(`/api/v1/positions/${id}/publication`)).data.data;
}
