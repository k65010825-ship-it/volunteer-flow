package com.volunteerflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationViews.NotificationPage;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class NotificationServiceTest {
  private final NotificationMapper notifications = mock(NotificationMapper.class);
  private final NotificationService service = new NotificationService(notifications);

  @Test
  void writeMapsThePublicCommandToAnIdempotentInsert() {
    when(notifications.insertIdempotent(any(Notification.class))).thenReturn(1);

    service.write(
        new NotificationWriter.NotificationCommand(
            7L,
            42L,
            "PROMOTION_OFFER",
            "递补邀请",
            "请及时确认",
            "PROMOTION_OFFER",
            99L,
            "promotion-offer:99:created"));

    ArgumentCaptor<Notification> inserted = ArgumentCaptor.forClass(Notification.class);
    verify(notifications).insertIdempotent(inserted.capture());
    assertThat(inserted.getValue().getId()).isNotNull();
    assertThat(inserted.getValue().getOrganizationId()).isEqualTo(7L);
    assertThat(inserted.getValue().getUserId()).isEqualTo(42L);
    assertThat(inserted.getValue().getNotificationType()).isEqualTo("PROMOTION_OFFER");
    assertThat(inserted.getValue().getTitle()).isEqualTo("递补邀请");
    assertThat(inserted.getValue().getContent()).isEqualTo("请及时确认");
    assertThat(inserted.getValue().getBusinessType()).isEqualTo("PROMOTION_OFFER");
    assertThat(inserted.getValue().getBusinessId()).isEqualTo(99L);
    assertThat(inserted.getValue().getDeduplicationKey())
        .isEqualTo("promotion-offer:99:created");
  }

  @Test
  void writeManyDeduplicatesRecipientsAndKeepsOneStableBusinessKeyPerUser() {
    when(notifications.insertManyIdempotent(anyList())).thenReturn(2);
    var template =
        new NotificationWriter.NotificationTemplate(
            7L,
            "ACTIVITY_CHANGE",
            "活动变更",
            "集合地点已调整",
            "ACTIVITY",
            99L,
            "activity-change:99:501");

    int attempted = service.writeMany(List.of(42L, 42L, 43L, 43L), template);

    assertThat(attempted).isEqualTo(2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Notification>> rows = ArgumentCaptor.forClass(List.class);
    verify(notifications).insertManyIdempotent(rows.capture());
    assertThat(rows.getValue()).extracting(Notification::getUserId).containsExactly(42L, 43L);
    assertThat(rows.getValue())
        .extracting(Notification::getDeduplicationKey)
        .containsExactly("activity-change:99:501", "activity-change:99:501");
    assertThat(rows.getValue()).extracting(Notification::getId).doesNotContainNull();
    assertThat(rows.getValue()).extracting(Notification::getId).doesNotHaveDuplicates();
  }

  @Test
  void emptyBulkWriteDoesNotIssueInvalidSql() {
    var template =
        new NotificationWriter.NotificationTemplate(
            7L, "TYPE", "title", "content", "BUSINESS", 99L, "business:99:event");

    assertThat(service.writeMany(List.of(), template)).isZero();

    verify(notifications, never()).insertManyIdempotent(anyList());
  }

  @Test
  void writeManyPropagatesNonDuplicateMapperFailures() {
    var failure = new DataIntegrityViolationException("title is too long");
    when(notifications.insertManyIdempotent(anyList())).thenThrow(failure);
    var template =
        new NotificationWriter.NotificationTemplate(
            7L, "TYPE", "title", "content", "BUSINESS", 99L, "business:99:event");

    assertThatThrownBy(() -> service.writeMany(List.of(42L), template)).isSameAs(failure);
  }

  @Test
  void mapperUsesNarrowDuplicateNoopInsteadOfInsertIgnore() throws Exception {
    String singleSql = annotationSql("insertIdempotent", Insert.class, Notification.class);
    String bulkSql = annotationSql("insertManyIdempotent", Insert.class, List.class);

    assertThat(singleSql).containsIgnoringCase("ON DUPLICATE KEY UPDATE id=id");
    assertThat(bulkSql).containsIgnoringCase("ON DUPLICATE KEY UPDATE id=id");
    assertThat(singleSql).doesNotContainIgnoringCase("INSERT IGNORE");
    assertThat(bulkSql).doesNotContainIgnoringCase("INSERT IGNORE");
  }

  @Test
  void equalCreateTimesUseIdAsTheSecondCursorComponent() {
    LocalDateTime tiedTime = LocalDateTime.of(2026, 9, 20, 12, 30, 0, 123_000_000);
    when(notifications.selectPage(42L, null, null, 3))
        .thenReturn(
            List.of(
                notification(103L, 42L, tiedTime),
                notification(102L, 42L, tiedTime),
                notification(101L, 42L, tiedTime)));

    NotificationPage first = service.page(42L, null, 2);

    assertThat(first.items()).extracting(item -> item.id()).containsExactly(103L, 102L);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.nextCursor()).isNotBlank();
    when(notifications.selectPage(42L, tiedTime, 102L, 3))
        .thenReturn(
            List.of(
                notification(101L, 42L, tiedTime),
                notification(100L, 42L, tiedTime.minusNanos(1_000))));

    NotificationPage second = service.page(42L, first.nextCursor(), 2);

    verify(notifications).selectPage(42L, tiedTime, 102L, 3);
    assertThat(second.items()).extracting(item -> item.id()).containsExactly(101L, 100L);
    assertThat(second.hasMore()).isFalse();
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  void mapperCursorSqlUsesCreateTimeThenIdAndRequestsTheServiceLimit() throws Exception {
    String sql =
        annotationSql(
            "selectPage", Select.class, Long.class, LocalDateTime.class, Long.class, int.class);

    assertThat(sql)
        .containsIgnoringCase(
            "create_time < #{cursorTime} OR (create_time = #{cursorTime} AND id < #{cursorId})")
        .containsIgnoringCase("ORDER BY create_time DESC, id DESC")
        .containsIgnoringCase("LIMIT #{limit}");
  }

  @Test
  void malformedCursorIsRejectedBeforeQueryingTheMapper() {
    assertThatThrownBy(() -> service.page(42L, "not-a-cursor", 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.code()).isEqualTo("INVALID_NOTIFICATION_CURSOR");
            });
    verifyNoInteractions(notifications);
  }

  @Test
  void userCannotMarkAnotherUsersNotificationAsRead() {
    when(notifications.markRead(42L, 900L)).thenReturn(0);
    when(notifications.existsForUser(42L, 900L)).thenReturn(false);

    assertThatThrownBy(() -> service.markRead(42L, 900L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(error.code()).isEqualTo("NOTIFICATION_NOT_FOUND");
            });
    verify(notifications).existsForUser(42L, 900L);
  }

  @Test
  void markingAnOwnedAlreadyReadNotificationIsIdempotent() {
    when(notifications.markRead(42L, 900L)).thenReturn(0);
    when(notifications.existsForUser(42L, 900L)).thenReturn(true);

    assertThatCode(() -> service.markRead(42L, 900L)).doesNotThrowAnyException();
  }

  @Test
  void unreadAndReadAllStayScopedToTheSuppliedUser() {
    when(notifications.countUnread(42L)).thenReturn(5L);
    when(notifications.markAllRead(42L)).thenReturn(5);

    assertThat(service.unreadCount(42L)).isEqualTo(5L);
    assertThat(service.markAllRead(42L)).isEqualTo(5);

    verify(notifications).countUnread(42L);
    verify(notifications).markAllRead(42L);
  }

  private Notification notification(Long id, Long userId, LocalDateTime createTime) {
    Notification notification = new Notification();
    notification.setId(id);
    notification.setOrganizationId(7L);
    notification.setUserId(userId);
    notification.setNotificationType("TYPE");
    notification.setTitle("title-" + id);
    notification.setContent("content-" + id);
    notification.setBusinessType("BUSINESS");
    notification.setBusinessId(99L);
    notification.setDeduplicationKey("business:99:" + id);
    notification.setCreateTime(createTime);
    return notification;
  }

  private <A extends java.lang.annotation.Annotation> String annotationSql(
      String methodName, Class<A> annotationType, Class<?>... parameterTypes) throws Exception {
    Method method = NotificationMapper.class.getDeclaredMethod(methodName, parameterTypes);
    A annotation = method.getAnnotation(annotationType);
    assertThat(annotation).isNotNull();
    String[] value;
    if (annotation instanceof Insert insert) {
      value = insert.value();
    } else if (annotation instanceof Select select) {
      value = select.value();
    } else {
      throw new AssertionError("Unsupported SQL annotation: " + annotationType.getName());
    }
    return String.join(" ", value)
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
