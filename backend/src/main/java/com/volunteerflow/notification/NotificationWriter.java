package com.volunteerflow.notification;

import java.util.Collection;

/**
 * Narrow same-transaction boundary used by business services that produce notifications.
 * The type identifies the event; businessType/businessId identify its navigable resource.
 * Position changes/publications use type POSITION_CHANGE but ACTIVITY and the parent activity ID,
 * never POSITION and a position ID. Their deduplication keys still identify the position event.
 */
public interface NotificationWriter {
  void write(NotificationCommand command);

  int writeMany(Collection<Long> userIds, NotificationTemplate template);

  record NotificationCommand(
      Long organizationId,
      Long userId,
      String type,
      String title,
      String content,
      String businessType,
      Long businessId,
      String deduplicationKey) {}

  record NotificationTemplate(
      Long organizationId,
      String type,
      String title,
      String content,
      String businessType,
      Long businessId,
      String deduplicationKey) {}
}
