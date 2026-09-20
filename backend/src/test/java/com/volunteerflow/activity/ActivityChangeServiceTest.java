package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityChangeService.ActivityChangeRequest;
import com.volunteerflow.activity.ActivityChangeService.ActivityChangeResult;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.audit.AuditLogMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationWriter.NotificationCommand;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.CapturingNotificationWriter;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

class ActivityChangeServiceTest {
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final RegistrationCycleMapper registrationCycles = mock(RegistrationCycleMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService audit = mock(AuditService.class);
  private final CapturingNotificationWriter notifications = new CapturingNotificationWriter();
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC);
  private final ActivityChangeService service =
      new ActivityChangeService(
          activities, registrationCycles, authorization, audit, notifications, clock);

  @BeforeEach
  void persistSingleRowWrites() {
    when(activities.updatePublishedFields(eq(1L), eq(100L), anyMap())).thenReturn(1);
  }

  @Test
  void changesEveryAllowedFieldAndUsesExplicitSnapshotsForAuditAndNotificationDeduplication() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    when(audit.recordChange(
            eq(100L),
            eq(7L),
            eq("activity.changed"),
            eq("activity"),
            eq(1L),
            anyMap(),
            anyMap(),
            eq("场地调整")))
        .thenReturn(501L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of(21L, 22L));
    notifications.beforeWrite(
        () ->
            verify(audit)
                .recordChange(
                    eq(100L),
                    eq(7L),
                    eq("activity.changed"),
                    eq("activity"),
                    eq(1L),
                    anyMap(),
                    anyMap(),
                    eq("场地调整")));

    ActivityChangeRequest request =
        new ActivityChangeRequest(
            "新标题",
            "新介绍",
            "新地点",
            LocalDateTime.of(2026, 9, 26, 18, 0),
            LocalDateTime.of(2026, 9, 29, 10, 0),
            LocalDateTime.of(2026, 10, 2, 9, 0),
            LocalDateTime.of(2026, 10, 2, 13, 0),
            "场地调整");

    ActivityChangeResult result = service.changeActivity(7L, 1L, request);

    Map<String, Object> expectedBefore =
        Map.of(
            "title", "旧标题",
            "description", "旧介绍",
            "location", "旧地点",
            "registrationEndAt", LocalDateTime.of(2026, 9, 25, 18, 0),
            "freeCancelDeadlineAt", LocalDateTime.of(2026, 9, 28, 10, 0),
            "activityStartAt", LocalDateTime.of(2026, 10, 1, 9, 0),
            "activityEndAt", LocalDateTime.of(2026, 10, 1, 13, 0));
    Map<String, Object> expectedAfter =
        Map.of(
            "title", "新标题",
            "description", "新介绍",
            "location", "新地点",
            "registrationEndAt", LocalDateTime.of(2026, 9, 26, 18, 0),
            "freeCancelDeadlineAt", LocalDateTime.of(2026, 9, 29, 10, 0),
            "activityStartAt", LocalDateTime.of(2026, 10, 2, 9, 0),
            "activityEndAt", LocalDateTime.of(2026, 10, 2, 13, 0));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changedFields = ArgumentCaptor.forClass(Map.class);
    verify(activities).updatePublishedFields(eq(1L), eq(100L), changedFields.capture());
    assertThat(changedFields.getValue()).containsExactlyInAnyOrderEntriesOf(expectedAfter);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
    verify(audit)
        .recordChange(
            eq(100L),
            eq(7L),
            eq("activity.changed"),
            eq("activity"),
            eq(1L),
            before.capture(),
            after.capture(),
            eq("场地调整"));
    assertThat(before.getValue()).containsExactlyInAnyOrderEntriesOf(expectedBefore);
    assertThat(after.getValue()).containsExactlyInAnyOrderEntriesOf(expectedAfter);
    assertThat(result.activity()).isSameAs(activity);
    assertThat(result.auditId()).isEqualTo(501L);
    assertThat(result.notifiedRecipients()).isEqualTo(2);
    assertThat(activity.getVersion()).isEqualTo(4);
    assertThat(notifications.commands())
        .extracting(NotificationCommand::userId)
        .containsExactly(21L, 22L);
    assertThat(notifications.commands())
        .extracting(NotificationCommand::deduplicationKey)
        .containsOnly("activity-change:1:501");
    assertThat(notifications.commands())
        .extracting(NotificationCommand::type, NotificationCommand::businessType)
        .containsOnly(org.assertj.core.groups.Tuple.tuple("ACTIVITY_CHANGE", "ACTIVITY"));
    var order = inOrder(activities, audit, registrationCycles);
    order.verify(activities).selectByIdForUpdate(1L);
    order.verify(activities).updatePublishedFields(eq(1L), eq(100L), anyMap());
    order
        .verify(audit)
        .recordChange(
            eq(100L),
            eq(7L),
            eq("activity.changed"),
            eq("activity"),
            eq(1L),
            anyMap(),
            anyMap(),
            eq("场地调整"));
    order.verify(registrationCycles).selectAffectedUserIds(1L, NOW);
  }

  @Test
  void lockedFinalStateWinsOverAnEarlierPublishedSnapshot() {
    Activity initialSnapshot = publishedActivity();
    Activity locked = publishedActivity();
    locked.setStatus("CANCELED");
    when(activities.selectById(1L)).thenReturn(initialSnapshot);
    when(activities.selectByIdForUpdate(1L)).thenReturn(locked);

    assertBusinessError(
        () -> service.changeActivity(7L, 1L, changedTitleRequest()),
        HttpStatus.CONFLICT,
        "ACTIVITY_CHANGE_NOT_ALLOWED");

    verify(activities).selectByIdForUpdate(1L);
    verify(activities, never()).selectById(1L);
    verify(activities, never()).updatePublishedFields(eq(1L), eq(100L), anyMap());
    verifyNoInteractions(audit, registrationCycles);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void aRequestWithoutAnEffectiveDifferenceIsRejected() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);

    ActivityChangeRequest request =
        new ActivityChangeRequest(
            " 旧标题 ",
            "旧介绍",
            "旧地点",
            activity.getRegistrationEndAt(),
            activity.getFreeCancelDeadlineAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt(),
            "说明原因");

    assertBusinessError(
        () -> service.changeActivity(7L, 1L, request),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "NO_EFFECTIVE_CHANGE");

    verify(activities, never()).updatePublishedFields(eq(1L), eq(100L), anyMap());
    verifyNoInteractions(audit, registrationCycles);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void aBlankReasonIsRejectedBeforeAnyWrite() {
    when(activities.selectByIdForUpdate(1L)).thenReturn(publishedActivity());
    ActivityChangeRequest request = changedTitleRequest();
    request =
        new ActivityChangeRequest(
            request.title(),
            request.description(),
            request.location(),
            request.registrationEndAt(),
            request.freeCancelDeadlineAt(),
            request.activityStartAt(),
            request.activityEndAt(),
            "   ");

    ActivityChangeRequest blankReason = request;
    assertBusinessError(
        () -> service.changeActivity(7L, 1L, blankReason),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "ACTIVITY_CHANGE_REASON_REQUIRED");

    verify(activities, never()).updatePublishedFields(eq(1L), eq(100L), anyMap());
    verifyNoInteractions(audit, registrationCycles);
  }

  @Test
  void shorteningRegistrationDeadlineToNowIsRejected() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);

    ActivityChangeRequest request =
        copyWithTimes(
            changedTitleRequest(), NOW, activity.getActivityStartAt(), activity.getActivityEndAt());

    assertInvalidTime(request);
  }

  @Test
  void strictScheduleOrderingRejectsADeadlineEqualToActivityStart() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);

    ActivityChangeRequest request =
        copyWithTimes(
            changedTitleRequest(),
            activity.getActivityStartAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt());

    assertInvalidTime(request);
  }

  @Test
  void changingActivityTimesAfterTheCurrentActivityHasStartedIsRejected() {
    Activity activity = publishedActivity();
    activity.setActivityStartAt(NOW.minusMinutes(1));
    activity.setActivityEndAt(NOW.plusHours(3));
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    ActivityChangeRequest request =
        copyWithTimes(
            changedTitleRequest(),
            activity.getRegistrationEndAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt().plusHours(1));

    assertInvalidTime(request);
  }

  @Test
  void aChangedActivityStartCannotBeMovedToThePresent() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    ActivityChangeRequest request =
        copyWithTimes(changedTitleRequest(), NOW.minusHours(1), NOW, NOW.plusHours(4));

    assertInvalidTime(request);
  }

  @Test
  void freeCancellationDeadlineCannotBeAfterActivityStart() {
    Activity activity = publishedActivity();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    ActivityChangeRequest base = changedTitleRequest();
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            base.title(),
            base.description(),
            base.location(),
            base.registrationEndAt(),
            base.activityStartAt().plusSeconds(1),
            base.activityStartAt(),
            base.activityEndAt(),
            base.reason());

    assertInvalidTime(request);
  }

  @Test
  void zeroRowActivityUpdateFailsBeforeAuditAndNotification() {
    when(activities.selectByIdForUpdate(1L)).thenReturn(publishedActivity());
    when(activities.updatePublishedFields(eq(1L), eq(100L), anyMap())).thenReturn(0);

    assertThatThrownBy(() -> service.changeActivity(7L, 1L, changedTitleRequest()))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verifyNoInteractions(audit, registrationCycles);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void changeWorkflowDeclaresOneTransactionBoundary() throws Exception {
    Method method =
        ActivityChangeService.class.getDeclaredMethod(
            "changeActivity", Long.class, Long.class, ActivityChangeRequest.class);

    assertThat(method.getAnnotation(Transactional.class)).isNotNull();
  }

  @Test
  void activityUpdateSqlCanOnlyWriteTheSevenPublishedChangeFields() throws Exception {
    String sql =
        annotationSql(
            ActivityMapper.class,
            "updatePublishedFields",
            Update.class,
            Long.class,
            Long.class,
            Map.class);

    assertThat(sql)
        .contains("title=#{changes.title}")
        .contains("description=#{changes.description}")
        .contains("location=#{changes.location}")
        .contains("registration_end_at=#{changes.registrationEndAt}")
        .contains("free_cancel_deadline_at=#{changes.freeCancelDeadlineAt")
        .contains("activity_start_at=#{changes.activityStartAt}")
        .contains("activity_end_at=#{changes.activityEndAt}")
        .contains("version=version+1")
        .doesNotContain("registration_start_at=#{changes")
        .doesNotContain("organization_id=#{changes")
        .doesNotContain("status=#{changes")
        .doesNotContain("created_by=#{changes");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING_REVIEW", "CONFIRMED", "WAITLISTED"})
  void recipientQueryIncludesEveryActiveCycleStatus(String status) throws Exception {
    String sql = affectedRecipientsSql();

    assertThat(sql).contains("c.status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED')");
    assertThat(sql).contains("'" + status + "'");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "REJECTED", "CANCELED", "LATE_CANCELED", "PROMOTION_DECLINED", "PROMOTION_EXPIRED"
      })
  void recipientQueryExcludesEveryTerminalCycleStatus(String status) throws Exception {
    assertThat(affectedRecipientsSql()).doesNotContain("'" + status + "'");
  }

  @Test
  void recipientQueryAlsoIncludesOnlyPendingNonExpiredOffersAndDeduplicatesUsers()
      throws Exception {
    assertThat(affectedRecipientsSql())
        .contains("SELECT DISTINCT c.user_id")
        .contains("o.status='PENDING'")
        .contains("o.expires_at > #{now}")
        .contains("o.registration_cycle_id=c.id")
        .contains("o.organization_id=c.organization_id")
        .contains("o.activity_id=c.activity_id")
        .doesNotContain("CURRENT_TIMESTAMP");
  }

  @Test
  void auditRecordChangeSerializesExplicitMapsAndReturnsItsGeneratedId() {
    AuditLogMapper mapper = mock(AuditLogMapper.class);
    when(mapper.insert(org.mockito.ArgumentMatchers.any(AuditLog.class))).thenReturn(1);
    AuditService realAudit = new AuditService(mapper, new ObjectMapper());

    Long auditId =
        realAudit.recordChange(
            100L,
            7L,
            "activity.changed",
            "activity",
            1L,
            Map.of("title", "旧标题"),
            Map.of("title", "新标题"),
            "场地调整");

    ArgumentCaptor<AuditLog> inserted = ArgumentCaptor.forClass(AuditLog.class);
    verify(mapper).insert(inserted.capture());
    assertThat(auditId).isEqualTo(inserted.getValue().getId());
    assertThat(inserted.getValue().getBeforeJson()).isEqualTo("{\"title\":\"旧标题\"}");
    assertThat(inserted.getValue().getAfterJson()).isEqualTo("{\"title\":\"新标题\"}");
    assertThat(inserted.getValue().getReason()).isEqualTo("场地调整");
  }

  private Activity publishedActivity() {
    Activity activity = new Activity();
    activity.setId(1L);
    activity.setOrganizationId(100L);
    activity.setTitle("旧标题");
    activity.setDescription("旧介绍");
    activity.setLocation("旧地点");
    activity.setRegistrationStartAt(LocalDateTime.of(2026, 9, 1, 9, 0));
    activity.setRegistrationEndAt(LocalDateTime.of(2026, 9, 25, 18, 0));
    activity.setFreeCancelDeadlineAt(LocalDateTime.of(2026, 9, 28, 10, 0));
    activity.setActivityStartAt(LocalDateTime.of(2026, 10, 1, 9, 0));
    activity.setActivityEndAt(LocalDateTime.of(2026, 10, 1, 13, 0));
    activity.setStatus("PUBLISHED");
    activity.setVersion(3);
    return activity;
  }

  private ActivityChangeRequest changedTitleRequest() {
    Activity activity = publishedActivity();
    return new ActivityChangeRequest(
        "新标题",
        activity.getDescription(),
        activity.getLocation(),
        activity.getRegistrationEndAt(),
        activity.getFreeCancelDeadlineAt(),
        activity.getActivityStartAt(),
        activity.getActivityEndAt(),
        "信息更正");
  }

  private ActivityChangeRequest copyWithTimes(
      ActivityChangeRequest base,
      LocalDateTime registrationEndAt,
      LocalDateTime activityStartAt,
      LocalDateTime activityEndAt) {
    return new ActivityChangeRequest(
        base.title(),
        base.description(),
        base.location(),
        registrationEndAt,
        base.freeCancelDeadlineAt(),
        activityStartAt,
        activityEndAt,
        base.reason());
  }

  private void assertInvalidTime(ActivityChangeRequest request) {
    assertBusinessError(
        () -> service.changeActivity(7L, 1L, request),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_ACTIVITY_TIME");
    verify(activities, never()).updatePublishedFields(eq(1L), eq(100L), anyMap());
    verifyNoInteractions(audit, registrationCycles);
  }

  private void assertBusinessError(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
      HttpStatus status,
      String code) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(status);
              assertThat(error.code()).isEqualTo(code);
            });
  }

  private String affectedRecipientsSql() throws Exception {
    return annotationSql(
        RegistrationCycleMapper.class,
        "selectAffectedUserIds",
        Select.class,
        Long.class,
        LocalDateTime.class);
  }

  private <A extends java.lang.annotation.Annotation> String annotationSql(
      Class<?> owner,
      String methodName,
      Class<A> annotationType,
      Class<?>... parameterTypes)
      throws Exception {
    Method method = owner.getDeclaredMethod(methodName, parameterTypes);
    A annotation = method.getAnnotation(annotationType);
    assertThat(annotation).isNotNull();
    String[] lines;
    if (annotation instanceof Select select) {
      lines = select.value();
    } else if (annotation instanceof Update update) {
      lines = update.value();
    } else {
      throw new AssertionError("Unsupported SQL annotation: " + annotationType.getName());
    }
    return String.join(" ", lines).replaceAll("\\s+", " ").trim();
  }
}
