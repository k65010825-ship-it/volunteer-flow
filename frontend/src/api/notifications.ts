import { apiClient } from "./http";
import type { EntityId } from "./types";

export interface UserNotification {
  id: EntityId;
  type: string;
  title: string;
  content: string;
  businessType: string | null;
  businessId: EntityId | null;
  readAt: string | null;
  createTime: string;
}

export interface NotificationPage {
  items: UserNotification[];
  nextCursor: string | null;
  hasMore: boolean;
}

export async function listNotifications(params: {
  cursor?: string;
  size: number;
}): Promise<NotificationPage> {
  return (
    await apiClient.get<{ data: NotificationPage }>(
      "/api/v1/users/me/notifications",
      { params },
    )
  ).data.data;
}

export async function getUnreadCount(): Promise<number> {
  const value = (
    await apiClient.get<{ data: { unreadCount: number | string } }>(
      "/api/v1/users/me/notifications/unread-count",
    )
  ).data.data.unreadCount;
  // The backend serializes Long values as strings to preserve precision for identifiers.
  const count = Number(value);
  if (!Number.isSafeInteger(count) || count < 0)
    throw new Error("Invalid unread count");
  return count;
}

export async function markNotificationRead(id: EntityId): Promise<void> {
  await apiClient.post(`/api/v1/notifications/${id}/read`);
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiClient.post("/api/v1/users/me/notifications/read-all");
}
