import { isAxiosError } from "axios";
import { apiClient } from "./http";
import type {
  EntityId,
  OwnOffer,
  OwnRegistration,
  RegistrationAnswer,
  RegistrationForm,
  RegistrationResult,
  RegistrationState,
  RegistrationStatus,
} from "./types";

export async function getRegistrationForm(
  activityId: EntityId,
  positionId: EntityId,
): Promise<RegistrationForm> {
  return (
    await apiClient.get<{ data: RegistrationForm }>(
      `/api/v1/activities/${activityId}/registration-form`,
      { params: { positionId } },
    )
  ).data.data;
}

export async function submitRegistration(
  activityId: EntityId,
  body: { positionId: EntityId; answers: RegistrationAnswer[] },
): Promise<RegistrationResult> {
  return (
    await apiClient.post<{ data: RegistrationResult }>(
      `/api/v1/activities/${activityId}/registrations`,
      body,
    )
  ).data.data;
}

export async function listMyRegistrations(): Promise<OwnRegistration[]> {
  return (
    await apiClient.get<{ data: OwnRegistration[] }>(
      "/api/v1/users/me/registrations",
    )
  ).data.data;
}

export async function getRegistration(id: EntityId): Promise<OwnRegistration> {
  return (
    await apiClient.get<{ data: OwnRegistration }>(
      `/api/v1/registrations/${id}`,
    )
  ).data.data;
}

export async function cancelRegistration(
  id: EntityId,
  body: { reason?: string },
): Promise<RegistrationState> {
  return (
    await apiClient.post<{ data: RegistrationState }>(
      `/api/v1/registrations/${id}/cancellation`,
      body,
    )
  ).data.data;
}

export async function respondToPromotionOffer(
  id: EntityId,
  decision: "ACCEPT" | "DECLINE",
): Promise<OwnOffer> {
  return (
    await apiClient.post<{ data: OwnOffer }>(
      `/api/v1/promotion-offers/${id}/responses`,
      { decision },
    )
  ).data.data;
}

export const registrationStatusLabel: Record<RegistrationStatus, string> = {
  PENDING_REVIEW: "待审核",
  CONFIRMED: "已录取",
  WAITLISTED: "候补中",
  REJECTED: "未通过审核",
  CANCELED: "已取消",
  LATE_CANCELED: "迟取消",
  PROMOTION_DECLINED: "已拒绝递补",
  PROMOTION_EXPIRED: "递补已过期",
};

export function registrationErrorCode(error: unknown): string | undefined {
  return isAxiosError<{ code?: string }>(error)
    ? error.response?.data?.code
    : undefined;
}

export function registrationErrorMessage(error: unknown): string {
  const code = registrationErrorCode(error);
  const messages: Record<string, string> = {
    REGISTRATION_ALREADY_ACTIVE: "你在此活动已有有效报名，请前往我的报名查看。",
    CANCELLATION_REASON_REQUIRED: "已超过免费取消时间，请填写取消原因。",
    INVALID_CANCELLATION_REASON: "取消原因不能超过 500 个字符。",
    REQUIRED_ANSWER_MISSING: "请完成必填问题。",
    INVALID_ANSWER: "报名答案无效，请检查后重试。",
    ACTIVITY_NOT_PUBLISHED: "活动当前未开放报名。",
    REGISTRATION_WINDOW_CLOSED: "当前不在报名时间内。",
  };
  if (code && messages[code]) return messages[code];
  if (isAxiosError(error) && error.response?.status === 409) {
    return "状态已变化或邀请已过期，请刷新后重试。";
  }
  if (isAxiosError(error) && error.response?.status === 404) {
    return "记录不存在或你无权访问。";
  }
  return "操作失败，请检查网络后重试。";
}
