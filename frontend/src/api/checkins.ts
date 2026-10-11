import { isAxiosError } from "axios";
import { apiClient } from "./http";
import type { ManagedRegistration } from "./registrations";
import type { EntityId } from "./types";

export type AttendanceStatus = "PENDING" | "CHECKED_IN" | "NO_SHOW";
export interface CheckinSession {
  sessionId: EntityId;
  activityId: EntityId;
  sessionType: string;
  status: "OPEN" | "CLOSED" | "EXPIRED";
  opensAt: string;
  closesAt: string;
}
export interface OpenedSession {
  sessionId: EntityId; activityId: EntityId; opensAt: string; closesAt: string; code: string;
}
export interface OwnAttendance {
  registrationId: EntityId; registrationCycleId: EntityId; positionId: EntityId; positionName: string;
  attendanceStatus: AttendanceStatus; recordId: EntityId | null; checkedInAt: string | null;
}
export interface CurrentCheckin { session: CheckinSession | null; attendance: OwnAttendance | null }
export interface AttendanceTotals {
  confirmedCount: string; checkedInCount: string; noShowCount: string; pendingCount: string; checkinRate: number;
}
export interface PositionAttendance extends AttendanceTotals { positionId: EntityId; positionName: string }
export interface AttendanceSummary extends AttendanceTotals {
  activityId: EntityId; activityStatus: string; finalStatistics: boolean;
  positions: PositionAttendance[]; correctionSession: CheckinSession | null;
}
export interface CheckinResult {
  recordId: EntityId; activityId: EntityId; userId: EntityId; method: "SELF" | "MANUAL"; checkedInAt: string;
}
export type CheckinMember = ManagedRegistration & { attendanceStatus: AttendanceStatus };
export async function getCurrentCheckin(id: EntityId): Promise<CurrentCheckin> {
  return (await apiClient.get<{ data: CurrentCheckin }>(`/api/v1/activities/${id}/check-in-sessions/current`)).data.data;
}
export async function getAttendanceSummary(id: EntityId): Promise<AttendanceSummary> {
  return (await apiClient.get<{ data: AttendanceSummary }>(`/api/v1/activities/${id}/attendance-summary`)).data.data;
}
export async function openCheckinSession(id: EntityId, durationMinutes: number): Promise<OpenedSession> {
  return (await apiClient.post<{ data: OpenedSession }>(`/api/v1/activities/${id}/check-in-sessions`, { durationMinutes })).data.data;
}
export async function closeCheckinSession(id: EntityId): Promise<CheckinSession> {
  return (await apiClient.post<{ data: CheckinSession }>(`/api/v1/check-in-sessions/${id}/closure`)).data.data;
}
export async function submitCheckin(id: EntityId, code: string): Promise<CheckinResult> {
  return (await apiClient.post<{ data: CheckinResult }>(`/api/v1/check-in-sessions/${id}/check-ins`, { code: code.trim().toUpperCase() })).data.data;
}
export async function manualCheckin(id: EntityId, registrationId: EntityId, reason: string): Promise<CheckinResult> {
  return (await apiClient.post<{ data: CheckinResult }>(`/api/v1/check-in-sessions/${id}/manual-check-ins`, { registrationId, reason: reason.trim() })).data.data;
}
export async function listCheckinMembers(positionId: EntityId, page: number) {
  return (await apiClient.get<{ data: { items: CheckinMember[]; total: string } }>(`/api/v1/positions/${positionId}/registrations`, {
    params: { status: "CONFIRMED", page, size: 100 },
  })).data.data;
}
// Backend LocalDateTime projections are UTC (the services explicitly use ZoneOffset.UTC).
export function checkinTime(value: string): number {
  return Date.parse(/[zZ]|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}Z`);
}
export const attendanceLabel: Record<AttendanceStatus, string> = { PENDING: "待签到", CHECKED_IN: "已签到", NO_SHOW: "未到场" };
export function checkinErrorMessage(error: unknown): string {
  const code = isAxiosError<{ code?: string }>(error) ? error.response?.data?.code : undefined;
  const messages: Record<string, string> = {
    INVALID_CHECKIN_CODE: "签到码无效，请向现场管理员核对。",
    CHECKIN_SESSION_NOT_OPEN: "签到已关闭，请刷新或联系管理员补签。",
    REGISTRATION_NOT_CONFIRMED: "你尚未录取，不能参加本次签到。",
    ALREADY_CHECKED_IN: "已签到，请勿重复提交。",
    CHECKIN_SESSION_ALREADY_OPEN: "签到已开启，请刷新查看当前会话。",
    INVALID_CHECKIN_DURATION: "签到时长须为 5–120 分钟的整数。",
    INVALID_MANUAL_CHECKIN_REASON: "请填写 1–500 个字符的补签原因。",
    ACTIVITY_FINAL: "活动已结束，不能开启签到；已有会话仍可用于补签。",
    ACTIVITY_CHECKIN_NOT_ALLOWED: "活动当前不可签到，请刷新查看最新状态。",
    ATTENDANCE_NOT_PENDING: "该成员当前状态不能补签，请刷新。",
    CHECKIN_SESSION_NOT_FOUND: "签到会话不存在或你无权访问。",
  };
  if (code && messages[code]) return messages[code];
  if (isAxiosError(error) && error.response?.status === 403) return "你没有执行此操作的权限。";
  if (isAxiosError(error) && error.response?.status === 404) return "记录不存在或你无权访问。";
  return "操作失败，请检查网络并刷新重试。";
}
