package com.volunteerflow.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationViews.NotificationItem;
import com.volunteerflow.notification.NotificationViews.NotificationPage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class NotificationService implements NotificationWriter {
  private static final int MAX_PAGE_SIZE = 50;
  private static final int WRITE_BATCH_SIZE = 200;
  private static final int MAX_CURSOR_LENGTH = 512;
  private static final DateTimeFormatter CURSOR_TIME_FORMAT =
      DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final NotificationMapper notifications;

  public NotificationService(NotificationMapper notifications) {
    this.notifications = notifications;
  }

  @Override
  @Transactional
  public void write(NotificationCommand command) {
    Objects.requireNonNull(command, "command");
    notifications.insertIdempotent(notification(command));
  }

  @Override
  @Transactional
  public int writeMany(Collection<Long> userIds, NotificationTemplate template) {
    Objects.requireNonNull(userIds, "userIds");
    Objects.requireNonNull(template, "template");
    LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>(userIds);
    if (uniqueUserIds.isEmpty()) {
      return 0;
    }
    List<Notification> rows =
        uniqueUserIds.stream()
            .map(userId -> notification(Objects.requireNonNull(userId, "userId"), template))
            .toList();
    // Bound each SQL statement while retaining one surrounding caller transaction.
    for (int start = 0; start < rows.size(); start += WRITE_BATCH_SIZE) {
      int end = Math.min(start + WRITE_BATCH_SIZE, rows.size());
      notifications.insertManyIdempotent(rows.subList(start, end));
    }
    return rows.size();
  }

  @Transactional(readOnly = true)
  public NotificationPage page(Long userId, String cursor, int size) {
    int boundedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    NotificationCursor decoded = decode(cursor);
    List<Notification> rows =
        notifications.selectPage(
            userId, decoded == null ? null : decoded.createTime(),
            decoded == null ? null : decoded.id(), boundedSize + 1);
    boolean hasMore = rows.size() > boundedSize;
    int resultSize = Math.min(rows.size(), boundedSize);
    List<NotificationItem> items =
        rows.subList(0, resultSize).stream().map(NotificationItem::from).toList();
    String nextCursor =
        hasMore ? encode(rows.get(resultSize - 1).getCreateTime(), rows.get(resultSize - 1).getId())
            : null;
    return new NotificationPage(items, nextCursor, hasMore);
  }

  @Transactional(readOnly = true)
  public long unreadCount(Long userId) {
    return notifications.countUnread(userId);
  }

  @Transactional
  public void markRead(Long userId, Long notificationId) {
    if (notifications.markRead(userId, notificationId) == 1) {
      return;
    }
    // A read notification is an idempotent success; another user's ID remains indistinguishable
    // from a missing ID so ownership cannot be probed through this endpoint.
    if (!notifications.existsForUser(userId, notificationId)) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification was not found");
    }
  }

  @Transactional
  public int markAllRead(Long userId) {
    return notifications.markAllRead(userId);
  }

  private Notification notification(NotificationCommand command) {
    Notification notification = new Notification();
    notification.setId(IdWorker.getId(notification));
    notification.setOrganizationId(command.organizationId());
    notification.setUserId(command.userId());
    notification.setNotificationType(command.type());
    notification.setTitle(command.title());
    notification.setContent(command.content());
    notification.setBusinessType(command.businessType());
    notification.setBusinessId(command.businessId());
    notification.setDeduplicationKey(command.deduplicationKey());
    return notification;
  }

  private Notification notification(Long userId, NotificationTemplate template) {
    return notification(
        new NotificationCommand(
            template.organizationId(),
            userId,
            template.type(),
            template.title(),
            template.content(),
            template.businessType(),
            template.businessId(),
            template.deduplicationKey()));
  }

  private String encode(LocalDateTime createTime, Long id) {
    String value = CURSOR_TIME_FORMAT.format(createTime) + "|" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private NotificationCursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    if (cursor.length() > MAX_CURSOR_LENGTH) {
      throw invalidCursor();
    }
    try {
      String decoded =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] components = decoded.split("\\|", -1);
      if (components.length != 2) {
        throw invalidCursor();
      }
      LocalDateTime createTime = LocalDateTime.parse(components[0], CURSOR_TIME_FORMAT);
      Long id = Long.valueOf(components[1]);
      if (id <= 0) {
        throw invalidCursor();
      }
      return new NotificationCursor(createTime, id);
    } catch (IllegalArgumentException | DateTimeParseException exception) {
      throw invalidCursor();
    }
  }

  private BusinessException invalidCursor() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST,
        "INVALID_NOTIFICATION_CURSOR",
        "Notification cursor is invalid");
  }

  private record NotificationCursor(LocalDateTime createTime, Long id) {}
}
