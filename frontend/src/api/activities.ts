import { apiClient } from "./http";
import type {
  Activity,
  ActivityDetail,
  ActivityManagementDetail,
  ActivitySummary,
  EntityId,
  PositionManagementQuestion,
  Position,
  QuestionType,
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

type ManagementResponse = {
  activity: Activity;
  positions: Array<{ position: Position; questions: PositionManagementQuestion[] }>;
};

export async function getManagementActivity(
  id: EntityId,
): Promise<ActivityManagementDetail> {
  const response = await apiClient.get<{ data: ManagementResponse }>(
    `/api/v1/activities/${id}/management`,
  );
  return {
    activity: response.data.data.activity,
    positions: response.data.data.positions.map(({ position, questions }) => ({
      ...position,
      questions,
    })),
  };
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

export interface PositionQuestionRequest {
  type: QuestionType;
  title: string;
  required: boolean;
  options: string[] | null;
  sortOrder: number;
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

export async function createPositionQuestion(
  positionId: EntityId,
  body: PositionQuestionRequest,
): Promise<PositionManagementQuestion> {
  return (
    await apiClient.post(`/api/v1/positions/${positionId}/questions`, body)
  ).data.data;
}

export async function updatePositionQuestion(
  positionId: EntityId,
  questionId: EntityId,
  body: PositionQuestionRequest,
): Promise<PositionManagementQuestion> {
  return (
    await apiClient.put(
      `/api/v1/positions/${positionId}/questions/${questionId}`,
      body,
    )
  ).data.data;
}

export async function deletePositionQuestion(
  positionId: EntityId,
  questionId: EntityId,
): Promise<void> {
  await apiClient.delete(`/api/v1/positions/${positionId}/questions/${questionId}`);
}
