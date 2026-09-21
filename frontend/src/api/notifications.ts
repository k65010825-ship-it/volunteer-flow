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
  return (
    await apiClient.get<{ data: { unreadCount: number } }>(
      "/api/v1/users/me/notifications/unread-count",
    )
  ).data.data.unreadCount;
}

export async function markNotificationRead(id: EntityId): Promise<void> {
  await apiClient.post(`/api/v1/notifications/${id}/read`);
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiClient.post("/api/v1/users/me/notifications/read-all");
}
