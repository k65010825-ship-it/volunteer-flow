package com.volunteerflow.notification;

import java.time.LocalDateTime;
import java.util.List;

/** API-safe notification projections; internal ownership and deduplication fields stay hidden. */
public final class NotificationViews {
  private NotificationViews() {}

  public record NotificationItem(
      Long id,
      String type,
      String title,
      String content,
      String businessType,
      Long businessId,
      LocalDateTime readAt,
      LocalDateTime createTime) {
    static NotificationItem from(Notification notification) {
      return new NotificationItem(
          notification.getId(),
          notification.getNotificationType(),
          notification.getTitle(),
          notification.getContent(),
          notification.getBusinessType(),
          notification.getBusinessId(),
          notification.getReadAt(),
          notification.getCreateTime());
    }
  }

  public record NotificationPage(
      List<NotificationItem> items, String nextCursor, boolean hasMore) {}

  public record UnreadCount(long unreadCount) {}
}
