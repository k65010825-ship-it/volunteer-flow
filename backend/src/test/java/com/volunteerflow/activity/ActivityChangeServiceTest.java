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
import com.volunteerflow.activity.ActivityChangeService.PositionChangeRequest;
import com.volunteerflow.activity.ActivityChangeService.PositionChangeResult;
import com.volunteerflow.activity.ActivityChangeService.PositionPublicationResult;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.audit.AuditLogMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationWriter.NotificationCommand;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.CapturingNotificationWriter;
import com.volunteerflow.registration.PromotionService;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class ActivityChangeServiceTest {
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper activityQuestions = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper positionQuestions =
      mock(ActivityPositionQuestionMapper.class);
  private final RegistrationCycleMapper registrationCycles = mock(RegistrationCycleMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService audit = mock(AuditService.class);
  private final PromotionService promotions = mock(PromotionService.class);
  private final CapturingNotificationWriter notifications = new CapturingNotificationWriter();
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC);
  private final ActivityChangeService service =
      new ActivityChangeService(
          activities,
          positions,
          activityQuestions,
          positionQuestions,
          registrationCycles,
          authorization,
          audit,
          notifications,
          promotions,
          clock);

  @BeforeEach
  void persistSingleRowWrites() {
    when(activities.updatePublishedFields(eq(1L), eq(100L), anyMap())).thenReturn(1);
    when(positions.updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap())).thenReturn(1);
    when(positions.activateDraft(2L, 100L, 1L)).thenReturn(1);
  }

  @Test
  void positionChangeRejectsRegistrationModeChanges() {
    arrangeLockedPosition(activePosition());
    PositionChangeRequest request = validPositionChange(20, "REVIEW");

    assertBusinessError(
        () -> service.changePosition(7L, 2L, request),
        HttpStatus.CONFLICT,
        "POSITION_CHANGE_NOT_ALLOWED");

    verify(positions, never()).updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap());
    verifyNoInteractions(audit, registrationCycles, promotions);
  }

  @ParameterizedTest
  @ValueSource(strings = {"shorten", "shift"})
  void activityWindowChangeRejectsStrandedActivePositionWithoutWrites(String scenario) {
    Activity activity = publishedActivity();
    ActivityPosition inherited = activePosition();
    inherited.setServiceStartAt(null);
    inherited.setServiceEndAt(null);
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    when(positions.selectActiveByActivityForUpdate(100L, 1L))
        .thenReturn(List.of(inherited, activePosition()));
    LocalDateTime start = activity.getActivityStartAt();
    LocalDateTime end = activity.getActivityEndAt();
    if (scenario.equals("shorten")) end = end.minusHours(1);
    else { start = start.plusDays(1); end = end.plusDays(1); }
    ActivityChangeRequest request = copyWithTimes(
        changedTitleRequest(), activity.getRegistrationEndAt(), start, end);

    assertBusinessError(() -> service.changeActivity(7L, 1L, request),
        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POSITION_TIME");

    verify(activities, never()).updatePublishedFields(eq(1L), eq(100L), anyMap());
    verifyNoInteractions(audit, registrationCycles);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void activityWindowChangeLocksAllActivePositionsAfterActivityAndAllowsExactBoundaries() {
    Activity activity = publishedActivity();
    ActivityPosition explicit = activePosition();
    ActivityPosition inherited = activePosition();
    inherited.setId(3L);
    inherited.setServiceStartAt(null);
    inherited.setServiceEndAt(null);
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    when(positions.selectActiveByActivityForUpdate(100L, 1L))
        .thenReturn(List.of(explicit, inherited));
    ActivityChangeRequest request = copyWithTimes(changedTitleRequest(),
        activity.getRegistrationEndAt(), explicit.getServiceStartAt(), explicit.getServiceEndAt());

    ActivityChangeResult result = service.changeActivity(7L, 1L, request);

    assertThat(result.activity().getActivityStartAt()).isEqualTo(explicit.getServiceStartAt());
    assertThat(result.activity().getActivityEndAt()).isEqualTo(explicit.getServiceEndAt());
    var order = inOrder(activities, positions);
    order.verify(activities).selectByIdForUpdate(1L);
    order.verify(positions).selectActiveByActivityForUpdate(100L, 1L);
    order.verify(activities).updatePublishedFields(eq(1L), eq(100L), anyMap());
  }

  @Test
  void positionChangeRejectsCapacityDecrease() {
    arrangeLockedPosition(activePosition());

    assertBusinessError(
        () -> service.changePosition(7L, 2L, validPositionChange(19, "FIRST_COME")),
        HttpStatus.CONFLICT,
        "POSITION_CHANGE_NOT_ALLOWED");

    verify(positions, never()).updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap());
  }

  @Test
  void positionChangeRequiresServiceTimesInsideTheActivitySchedule() {
    ActivityPosition position = activePosition();
    arrangeLockedPosition(position);
    PositionChangeRequest request =
        new PositionChangeRequest(
            position.getName(),
            position.getDescription(),
            position.getCapacity(),
            position.getRegistrationMode(),
            position.getPromotionTimeoutMinutes(),
            publishedActivity().getActivityStartAt().minusMinutes(1),
            position.getServiceEndAt(),
            position.getMeetingLocation(),
            "调整服务时间");

    assertBusinessError(
        () -> service.changePosition(7L, 2L, request),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_POSITION_TIME");

    verify(positions, never()).updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap());
  }

  @Test
  void capacityIncreaseWritesAuditAndNotificationThenFillsOnlyAvailableSeats() {
    ActivityPosition position = activePosition();
    arrangeLockedPosition(position);
    when(audit.recordChange(
            eq(100L),
            eq(7L),
            eq("position.changed"),
            eq("activity_position"),
            eq(2L),
            anyMap(),
            anyMap(),
            eq("增加一个名额")))
        .thenReturn(601L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of(21L));

    PositionChangeResult result =
        service.changePosition(7L, 2L, validPositionChange(21, "FIRST_COME"));

    assertThat(result.position()).isSameAs(position);
    assertThat(result.auditId()).isEqualTo(601L);
    assertThat(result.notifiedRecipients()).isEqualTo(1);
    assertThat(position.getCapacity()).isEqualTo(21);
    assertThat(position.getVersion()).isEqualTo(3);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changes = ArgumentCaptor.forClass(Map.class);
    verify(positions).updatePublishedFields(eq(2L), eq(100L), eq(1L), changes.capture());
    assertThat(changes.getValue()).containsOnly(Map.entry("capacity", 21));
    verify(promotions).fillAvailableFirstComeSeats(position, 7L, "增加一个名额");
    assertThat(notifications.commands())
        .containsExactly(
            new NotificationCommand(
                100L,
                21L,
                "POSITION_CHANGE",
                "岗位信息变更",
                "礼仪岗已更新：增加一个名额",
                "ACTIVITY",
                1L,
                "position-change:2:601"));
    var order = inOrder(activities, positions, audit, registrationCycles, promotions);
    order.verify(activities).selectByIdForUpdate(1L);
    order.verify(positions).selectByIdForUpdate(2L);
    order.verify(positions).updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap());
    order
        .verify(audit)
        .recordChange(
            eq(100L),
            eq(7L),
            eq("position.changed"),
            eq("activity_position"),
            eq(2L),
            anyMap(),
            anyMap(),
            eq("增加一个名额"));
    order.verify(registrationCycles).selectAffectedUserIds(1L, NOW);
    order.verify(promotions).fillAvailableFirstComeSeats(position, 7L, "增加一个名额");
  }

  @Test
  void zeroRowPositionChangeFailsBeforeAuditNotificationAndCapacityFill() {
    arrangeLockedPosition(activePosition());
    when(positions.updatePublishedFields(eq(2L), eq(100L), eq(1L), anyMap())).thenReturn(0);

    assertThatThrownBy(
            () -> service.changePosition(7L, 2L, validPositionChange(21, "FIRST_COME")))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verifyNoInteractions(audit, registrationCycles, promotions);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void draftPositionPublicationLocksQuestionsActivatesAndNotifiesAffectedUsers() {
    ActivityPosition draft = activePosition();
    draft.setStatus("DRAFT");
    arrangeLockedPosition(draft);
    when(activityQuestions.selectIdsByActivityForUpdate(1L))
        .thenReturn(java.util.Collections.nCopies(4, 10L));
    when(positionQuestions.selectPositionIdsByActivityForUpdate(1L))
        .thenReturn(java.util.Collections.nCopies(6, 2L));
    when(audit.recordChange(
            eq(100L),
            eq(7L),
            eq("position.published"),
            eq("activity_position"),
            eq(2L),
            anyMap(),
            anyMap(),
            org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(602L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of(21L, 22L));

    PositionPublicationResult result = service.publishPosition(7L, 2L);

    assertThat(result.position()).isSameAs(draft);
    assertThat(result.auditId()).isEqualTo(602L);
    assertThat(result.notifiedRecipients()).isEqualTo(2);
    assertThat(draft.getStatus()).isEqualTo("ACTIVE");
    assertThat(draft.getVersion()).isEqualTo(3);
    assertThat(notifications.commands())
        .extracting(NotificationCommand::userId)
        .containsExactly(21L, 22L);
    assertThat(notifications.commands())
        .extracting(NotificationCommand::deduplicationKey)
        .containsOnly("position-change:2:602");
    assertThat(notifications.commands())
        .extracting(NotificationCommand::businessType, NotificationCommand::businessId)
        .containsOnly(org.assertj.core.groups.Tuple.tuple("ACTIVITY", 1L));
    var order = inOrder(activities, positions, activityQuestions, positionQuestions);
    order.verify(activities).selectByIdForUpdate(1L);
    order.verify(positions).selectByIdForUpdate(2L);
    order.verify(activityQuestions).selectIdsByActivityForUpdate(1L);
    order.verify(positionQuestions).selectPositionIdsByActivityForUpdate(1L);
    order.verify(positions).activateDraft(2L, 100L, 1L);
  }

  @Test
  void draftPositionPublicationRejectsMoreThanTenCombinedQuestions() {
    ActivityPosition draft = activePosition();
    draft.setStatus("DRAFT");
    arrangeLockedPosition(draft);
    when(activityQuestions.selectIdsByActivityForUpdate(1L))
        .thenReturn(java.util.Collections.nCopies(6, 10L));
    when(positionQuestions.selectPositionIdsByActivityForUpdate(1L))
        .thenReturn(java.util.Collections.nCopies(5, 2L));

    assertBusinessError(
        () -> service.publishPosition(7L, 2L),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "TOO_MANY_REGISTRATION_QUESTIONS");

    verify(positions, never()).activateDraft(2L, 100L, 1L);
    verifyNoInteractions(audit, registrationCycles);
  }

  @Test
  void draftPositionPublicationRevalidatesServiceTimesAgainstCurrentActivityWindow() {
    ActivityPosition draft = activePosition();
    draft.setStatus("DRAFT");
    when(positions.selectById(2L)).thenReturn(draft);
    Activity shortened = publishedActivity();
    shortened.setActivityStartAt(LocalDateTime.of(2026, 10, 1, 10, 0));
    when(activities.selectByIdForUpdate(1L)).thenReturn(shortened);
    when(positions.selectByIdForUpdate(2L)).thenReturn(draft);

    assertBusinessError(
        () -> service.publishPosition(7L, 2L),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_POSITION_TIME");

    verifyNoInteractions(activityQuestions, positionQuestions);
    verify(positions, never()).activateDraft(2L, 100L, 1L);
    verifyNoInteractions(audit, registrationCycles);
  }

  @Test
  void zeroRowDraftPublicationFailsBeforeAuditAndNotification() {
    ActivityPosition draft = activePosition();
    draft.setStatus("DRAFT");
    arrangeLockedPosition(draft);
    when(activityQuestions.selectIdsByActivityForUpdate(1L)).thenReturn(List.of());
    when(positionQuestions.selectPositionIdsByActivityForUpdate(1L)).thenReturn(List.of());
    when(positions.activateDraft(2L, 100L, 1L)).thenReturn(0);

    assertThatThrownBy(() -> service.publishPosition(7L, 2L))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verifyNoInteractions(audit, registrationCycles);
    assertThat(notifications.commands()).isEmpty();
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
  void omittedFreeCancellationDeadlineKeepsTheLockedValueAndDoesNotEnterTheDiff() {
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
            eq("信息更正")))
        .thenReturn(501L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of());
    LocalDateTime existingDeadline = activity.getFreeCancelDeadlineAt();
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            "新标题",
            activity.getDescription(),
            activity.getLocation(),
            activity.getRegistrationEndAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt(),
            "信息更正");

    ActivityChangeResult result = service.changeActivity(7L, 1L, request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changes = ArgumentCaptor.forClass(Map.class);
    verify(activities).updatePublishedFields(eq(1L), eq(100L), changes.capture());
    assertThat(changes.getValue()).containsOnly(Map.entry("title", "新标题"));
    assertThat(result.activity().getFreeCancelDeadlineAt()).isEqualTo(existingDeadline);
  }

  @Test
  void explicitNullFreeCancellationDeadlineClearsItAndEntersBothAuditSnapshots() {
    Activity activity = publishedActivity();
    LocalDateTime existingDeadline = activity.getFreeCancelDeadlineAt();
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    when(audit.recordChange(
            eq(100L),
            eq(7L),
            eq("activity.changed"),
            eq("activity"),
            eq(1L),
            anyMap(),
            anyMap(),
            eq("取消免费取消期限")))
        .thenReturn(502L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of());
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            activity.getTitle(),
            activity.getDescription(),
            activity.getLocation(),
            activity.getRegistrationEndAt(),
            null,
            activity.getActivityStartAt(),
            activity.getActivityEndAt(),
            "取消免费取消期限");

    ActivityChangeResult result = service.changeActivity(7L, 1L, request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changes = ArgumentCaptor.forClass(Map.class);
    verify(activities).updatePublishedFields(eq(1L), eq(100L), changes.capture());
    assertThat(changes.getValue()).containsOnlyKeys("freeCancelDeadlineAt");
    assertThat(changes.getValue().get("freeCancelDeadlineAt")).isNull();
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
            eq("取消免费取消期限"));
    assertThat(before.getValue())
        .containsOnly(Map.entry("freeCancelDeadlineAt", existingDeadline));
    assertThat(after.getValue()).containsOnlyKeys("freeCancelDeadlineAt");
    assertThat(after.getValue().get("freeCancelDeadlineAt")).isNull();
    assertThat(result.activity().getFreeCancelDeadlineAt()).isNull();
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
    activity.setRegistrationEndAt(NOW.minusHours(2));
    activity.setFreeCancelDeadlineAt(NOW.minusHours(3));
    activity.setActivityStartAt(NOW.minusMinutes(1));
    activity.setActivityEndAt(NOW.plusHours(3));
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            "新标题",
            activity.getDescription(),
            activity.getLocation(),
            activity.getRegistrationEndAt(),
            activity.getFreeCancelDeadlineAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt().plusHours(1),
            "调整活动时间");

    assertActivityTimeChangeRejected(request);
  }

  @Test
  void aChangedActivityStartCannotBeMovedToThePresent() {
    Activity activity = publishedActivity();
    activity.setRegistrationEndAt(NOW.minusHours(1));
    activity.setFreeCancelDeadlineAt(NOW.minusHours(2));
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            "新标题",
            activity.getDescription(),
            activity.getLocation(),
            activity.getRegistrationEndAt(),
            activity.getFreeCancelDeadlineAt(),
            NOW,
            NOW.plusHours(4),
            "调整活动时间");

    assertActivityTimeChangeRejected(request);
  }

  @Test
  void anActivityThatHasStartedStillAllowsANonTimeFieldChange() {
    Activity activity = publishedActivity();
    activity.setRegistrationEndAt(NOW.minusHours(2));
    activity.setFreeCancelDeadlineAt(NOW.minusHours(3));
    activity.setActivityStartAt(NOW.minusHours(1));
    activity.setActivityEndAt(NOW.plusHours(3));
    when(activities.selectByIdForUpdate(1L)).thenReturn(activity);
    when(audit.recordChange(
            eq(100L),
            eq(7L),
            eq("activity.changed"),
            eq("activity"),
            eq(1L),
            anyMap(),
            anyMap(),
            eq("更正标题")))
        .thenReturn(503L);
    when(registrationCycles.selectAffectedUserIds(1L, NOW)).thenReturn(List.of());
    ActivityChangeRequest request =
        new ActivityChangeRequest(
            "新标题",
            activity.getDescription(),
            activity.getLocation(),
            activity.getRegistrationEndAt(),
            activity.getFreeCancelDeadlineAt(),
            activity.getActivityStartAt(),
            activity.getActivityEndAt(),
            "更正标题");

    ActivityChangeResult result = service.changeActivity(7L, 1L, request);

    assertThat(result.activity().getTitle()).isEqualTo("新标题");
    verify(activities)
        .updatePublishedFields(1L, 100L, Map.of("title", "新标题"));
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

  @Test
  void positionChangeAndPublicationEachDeclareOneTransactionBoundary() throws Exception {
    Method change =
        ActivityChangeService.class.getDeclaredMethod(
            "changePosition", Long.class, Long.class, PositionChangeRequest.class);
    Method publication =
        ActivityChangeService.class.getDeclaredMethod("publishPosition", Long.class, Long.class);

    assertThat(change.getAnnotation(Transactional.class)).isNotNull();
    assertThat(change.getAnnotation(Transactional.class).isolation())
        .isEqualTo(Isolation.READ_COMMITTED);
    assertThat(publication.getAnnotation(Transactional.class)).isNotNull();
  }

  @Test
  void positionUpdateSqlCannotChangeModeStatusOrOwnership() throws Exception {
    String sql =
        annotationSql(
            ActivityPositionMapper.class,
            "updatePublishedFields",
            Update.class,
            Long.class,
            Long.class,
            Long.class,
            Map.class);

    assertThat(sql)
        .contains("name=#{changes.name}")
        .contains("description=#{changes.description}")
        .contains("capacity=#{changes.capacity}")
        .contains("promotion_timeout_minutes=#{changes.promotionTimeoutMinutes}")
        .contains("service_start_at=#{changes.serviceStartAt")
        .contains("service_end_at=#{changes.serviceEndAt")
        .contains("meeting_location=#{changes.meetingLocation")
        .contains("version=version+1")
        .contains("status='ACTIVE'")
        .doesNotContain("registration_mode=#{changes")
        .doesNotContain("organization_id=#{changes")
        .doesNotContain("activity_id=#{changes")
        .doesNotContain("status=#{changes");
  }

  @Test
  void positionPublicationSqlOnlyActivatesTheExpectedDraft() throws Exception {
    String sql =
        annotationSql(
            ActivityPositionMapper.class,
            "activateDraft",
            Update.class,
            Long.class,
            Long.class,
            Long.class);

    assertThat(sql)
        .contains("SET status='ACTIVE', version=version+1")
        .contains("id=#{positionId}")
        .contains("organization_id=#{organizationId}")
        .contains("activity_id=#{activityId}")
        .contains("status='DRAFT'");
  }

  private void arrangeLockedPosition(ActivityPosition position) {
    when(positions.selectById(2L)).thenReturn(position);
    when(activities.selectByIdForUpdate(1L)).thenReturn(publishedActivity());
    when(positions.selectByIdForUpdate(2L)).thenReturn(position);
  }

  private ActivityPosition activePosition() {
    ActivityPosition position = new ActivityPosition();
    position.setId(2L);
    position.setOrganizationId(100L);
    position.setActivityId(1L);
    position.setName("礼仪岗");
    position.setDescription("负责接待");
    position.setCapacity(20);
    position.setRegistrationMode("FIRST_COME");
    position.setPromotionTimeoutMinutes(120);
    position.setServiceStartAt(LocalDateTime.of(2026, 10, 1, 9, 30));
    position.setServiceEndAt(LocalDateTime.of(2026, 10, 1, 12, 30));
    position.setMeetingLocation("东门");
    position.setStatus("ACTIVE");
    position.setVersion(2);
    return position;
  }

  private PositionChangeRequest validPositionChange(int capacity, String registrationMode) {
    ActivityPosition position = activePosition();
    return new PositionChangeRequest(
        position.getName(),
        position.getDescription(),
        capacity,
        registrationMode,
        position.getPromotionTimeoutMinutes(),
        position.getServiceStartAt(),
        position.getServiceEndAt(),
        position.getMeetingLocation(),
        "增加一个名额");
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

  private void assertActivityTimeChangeRejected(ActivityChangeRequest request) {
    assertThatThrownBy(() -> service.changeActivity(7L, 1L, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.code()).isEqualTo("INVALID_ACTIVITY_TIME");
              assertThat(error.getMessage())
                  .isEqualTo("Activity times cannot be changed after activity start");
            });
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
