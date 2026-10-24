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
export interface ActivityPage {
  data: ActivitySummary[];
  meta: { total: number; page: number; size: number };
}
export async function listActivitiesPage(
  orgId: EntityId,
  page = 1,
  size = 20,
): Promise<ActivityPage> {
  return (
    await apiClient.get(`/api/v1/organizations/${orgId}/activities`, {
      params: { page, size },
    })
  ).data;
}
export interface ActivityDraftRequest {
  title: string;
  description: string;
  location: string;
  registrationStartAt: string;
  registrationEndAt: string;
  freeCancelDeadlineAt?: string | null;
  activityStartAt: string;
  activityEndAt: string;
}
export async function createActivity(
  orgId: EntityId,
  body: ActivityDraftRequest,
): Promise<Activity> {
  return (
    await apiClient.post(`/api/v1/organizations/${orgId}/activities`, body)
  ).data.data;
}
export async function updateActivity(
  id: EntityId,
  body: ActivityDraftRequest,
): Promise<Activity> {
  return (await apiClient.put(`/api/v1/activities/${id}`, body)).data.data;
}
export async function publishActivity(id: EntityId): Promise<Activity> {
  return (await apiClient.post(`/api/v1/activities/${id}/publication`)).data
    .data;
}
export async function cancelActivity(
  id: EntityId,
  reason: string,
): Promise<Activity> {
  return (
    await apiClient.post(`/api/v1/activities/${id}/cancellation`, { reason })
  ).data.data;
}
export async function completeActivity(
  id: EntityId,
  reason: string,
): Promise<Activity> {
  return (
    await apiClient.post(`/api/v1/activities/${id}/completion`, { reason })
  ).data.data;
}
export async function createActivityQuestion(
  id: EntityId,
  body: PositionQuestionRequest,
): Promise<PositionManagementQuestion> {
  return (await apiClient.post(`/api/v1/activities/${id}/questions`, body)).data
    .data;
}
export async function updateActivityQuestion(
  id: EntityId,
  questionId: EntityId,
  body: PositionQuestionRequest,
): Promise<PositionManagementQuestion> {
  return (
    await apiClient.put(
      `/api/v1/activities/${id}/questions/${questionId}`,
      body,
    )
  ).data.data;
}
export async function deleteActivityQuestion(
  id: EntityId,
  questionId: EntityId,
): Promise<void> {
  await apiClient.delete(`/api/v1/activities/${id}/questions/${questionId}`);
}
export async function listActivityQuestions(
  id: EntityId,
): Promise<PositionManagementQuestion[]> {
  return (await apiClient.get(`/api/v1/activities/${id}/questions`)).data.data;
}
export async function listPositionQuestions(
  id: EntityId,
): Promise<PositionManagementQuestion[]> {
  return (await apiClient.get(`/api/v1/positions/${id}/questions`)).data.data;
}
export async function getActivity(id: EntityId): Promise<ActivityDetail> {
  return (await apiClient.get(`/api/v1/activities/${id}`)).data.data;
}

export async function getActivityDraft(id: EntityId): Promise<ActivityDetail> {
  return (await apiClient.get(`/api/v1/activities/${id}/draft`)).data.data;
}

export async function listActivityDraftsPage(
  orgId: EntityId,
  page = 1,
  size = 20,
): Promise<ActivityPage> {
  return (
    await apiClient.get(`/api/v1/organizations/${orgId}/activity-drafts`, {
      params: { page, size },
    })
  ).data;
}

export async function updateDraftPosition(
  id: EntityId,
  body: PositionDraftRequest,
): Promise<Position> {
  return (await apiClient.put(`/api/v1/positions/${id}/draft`, body)).data.data;
}

export async function deleteDraftPosition(id: EntityId): Promise<void> {
  await apiClient.delete(`/api/v1/positions/${id}/draft`);
}

type ManagementResponse = {
  activity: Activity;
  positions: Array<{
    position: Position;
    questions: PositionManagementQuestion[];
  }>;
  commonQuestionCount: number;
};

export async function getManagementActivity(
  id: EntityId,
): Promise<ActivityManagementDetail> {
  const response = await apiClient.get<{ data: ManagementResponse }>(
    `/api/v1/activities/${id}/management`,
  );
  return {
    activity: response.data.data.activity,
    commonQuestionCount: response.data.data.commonQuestionCount,
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
  return (
    await apiClient.post(`/api/v1/activities/${activityId}/positions`, body)
  ).data.data;
}

export async function publishPosition(
  id: EntityId,
): Promise<PositionChangeResult> {
  return (await apiClient.post(`/api/v1/positions/${id}/publication`)).data
    .data;
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
  await apiClient.delete(
    `/api/v1/positions/${positionId}/questions/${questionId}`,
  );
}
