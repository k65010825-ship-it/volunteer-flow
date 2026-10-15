package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationWriter.NotificationCommand;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.CapturingNotificationWriter;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class ActivityServiceTest {
  private final ActivityMapper activityMapper = mock(ActivityMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper activityQuestionMapper = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper positionQuestionMapper =
      mock(ActivityPositionQuestionMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final RegistrationCycleMapper cycleMapper = mock(RegistrationCycleMapper.class);
  private final CapturingNotificationWriter notifications = new CapturingNotificationWriter();
  private final ActivityDetailCache detailCache = mock(ActivityDetailCache.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
  private final ActivityService service =
      new ActivityService(
          activityMapper,
          positionMapper,
          activityQuestionMapper,
          positionQuestionMapper,
          authorization,
          auditService,
          cycleMapper,
          notifications,
          detailCache,
          clock);

  @BeforeEach
  void persistSingleRowWrites() {
    when(activityMapper.updateById(any(Activity.class))).thenReturn(1);
    when(positionMapper.insert(any(ActivityPosition.class))).thenReturn(1);
  }

  @Test
  void publishedActivityAddsDraftPositionWithChangePermission() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(published);

    ActivityPosition result = service.addPosition(7L, 1L, validPositionRequest());

    assertThat(result.getStatus()).isEqualTo("DRAFT");
    verify(authorization).requirePermission(7L, 100L, "activity:change");
    verify(positionMapper).insert(result);
    verify(auditService)
        .record(100L, 7L, "activity.position.created", "activity_position", result.getId(), null);
  }

  @Test
  void draftActivityStillAddsActivePositionWithCreatePermission() {
    Activity draft = validDraft();
    when(activityMapper.selectById(1L)).thenReturn(draft);
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(draft);

    ActivityPosition result = service.addPosition(7L, 1L, validPositionRequest());

    assertThat(result.getStatus()).isEqualTo("ACTIVE");
    verify(authorization).requirePermission(7L, 100L, "activity:create");
  }

  @Test
  void publishedPositionServiceTimesMustStayWithinActivity() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(published);
    ActivityService.PositionRequest request =
        new ActivityService.PositionRequest(
            "礼仪岗",
            "负责接待",
            10,
            "FIRST_COME",
            120,
            published.getActivityStartAt().minusMinutes(1),
            published.getActivityEndAt(),
            "东门");

    assertThatThrownBy(() -> service.addPosition(7L, 1L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("INVALID_POSITION_TIME");

    verify(positionMapper, never()).insert(any(ActivityPosition.class));
  }

  @Test
  void zeroRowPublishedPositionInsertFailsBeforeAudit() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(published);
    when(positionMapper.insert(any(ActivityPosition.class))).thenReturn(0);

    assertThatThrownBy(() -> service.addPosition(7L, 1L, validPositionRequest()))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verifyNoInteractions(auditService);
  }

  @Test
  void nonMemberCannotObserveFinalActivityStateWhenAddingPosition() {
    Activity canceled = validDraft();
    canceled.setStatus("CANCELED");
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(canceled);
    BusinessException hidden =
        new BusinessException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "ORGANIZATION_NOT_FOUND",
            "Organization was not found");
    doThrow(hidden).when(authorization).requireMembership(99L, 100L);

    assertThatThrownBy(() -> service.addPosition(99L, 1L, validPositionRequest()))
        .isSameAs(hidden);

    verify(authorization, never()).requirePermission(anyLong(), anyLong(), anyString());
    verify(positionMapper, never()).insert(any(ActivityPosition.class));
  }

  @Test
  void managementDetailRequiresChangePermissionAndIncludesDraftPositionsOnlyForManagers() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    ActivityPosition active = activePosition();
    ActivityPosition draft = activePosition();
    draft.setId(31L);
    draft.setStatus("DRAFT");
    ActivityPositionQuestion question = new ActivityPositionQuestion();
    question.setId(41L);
    question.setPositionId(31L);
    question.setTitle("接待经验");
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(positionMapper.selectManageableByActivity(100L, 1L)).thenReturn(List.of(active, draft));
    when(activityQuestionMapper.countByActivity(1L)).thenReturn(3L);
    when(positionQuestionMapper.selectByPosition(100L, 1L, 30L)).thenReturn(List.of());
    when(positionQuestionMapper.selectByPosition(100L, 1L, 31L)).thenReturn(List.of(question));

    ActivityService.ActivityManagementDetail detail = service.managementDetail(7L, 1L);

    assertThat(detail.positions()).extracting(ActivityService.ManagedPosition::position)
        .containsExactly(active, draft);
    assertThat(detail.positions().get(1).questions()).containsExactly(question);
    assertThat(detail.commonQuestionCount()).isEqualTo(3);
    verify(authorization).requirePermission(7L, 100L, "activity:change");
    verifyNoInteractions(detailCache);
  }

  @Test
  void managementDetailDoesNotQueryPositionsWhenCrossOrganizationUserIsRejected() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(1L)).thenReturn(published);
    BusinessException denied =
        new BusinessException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "ORGANIZATION_NOT_FOUND",
            "Organization was not found");
    doThrow(denied).when(authorization).requireMembership(99L, 100L);

    assertThatThrownBy(() -> service.managementDetail(99L, 1L)).isSameAs(denied);

    verifyNoInteractions(positionMapper, positionQuestionMapper);
  }

  @Test
  void publicDetailContinuesToReadOnlyActivePositions() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    ActivityPosition active = activePosition();
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(positionMapper.selectActiveByActivity(100L, 1L)).thenReturn(List.of(active));

    ActivityService.ActivityDetail detail = service.detail(7L, 1L);

    assertThat(detail.positions()).containsExactly(active);
    verify(positionMapper, never()).selectManageableByActivity(anyLong(), anyLong());
    verify(detailCache).put(detail);
  }

  @Test
  void publishedDetailCacheHitStillRequiresMembershipAndSkipsDatabaseReads() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    ActivityService.ActivityDetail cached =
        new ActivityService.ActivityDetail(published, List.of(activePosition()));
    when(detailCache.get(1L)).thenReturn(Optional.of(cached));

    ActivityService.ActivityDetail result = service.detail(7L, 1L);

    assertThat(result).isSameAs(cached);
    verify(authorization).requireMembership(7L, 100L);
    verifyNoInteractions(activityMapper, positionMapper);
    verify(detailCache, never()).put(any());
  }

  @Test
  void cacheMissFallsBackToDatabaseAndPopulatesPublishedDetail() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    ActivityPosition active = activePosition();
    when(detailCache.get(1L)).thenReturn(Optional.empty());
    when(activityMapper.selectById(1L)).thenReturn(published);
    when(positionMapper.selectActiveByActivity(100L, 1L)).thenReturn(List.of(active));

    ActivityService.ActivityDetail result = service.detail(7L, 1L);

    assertThat(result.activity()).isSameAs(published);
    assertThat(result.positions()).containsExactly(active);
    verify(detailCache).put(result);
  }

  @Test
  void draftDetailUsesDatabaseAndIsNotCached() {
    Activity draft = validDraft();
    when(detailCache.get(1L)).thenReturn(Optional.empty());
    when(activityMapper.selectById(1L)).thenReturn(draft);
    when(positionMapper.selectActiveByActivity(100L, 1L)).thenReturn(List.of());

    ActivityService.ActivityDetail result = service.detail(7L, 1L);

    assertThat(result.activity()).isSameAs(draft);
    verify(authorization).requirePermission(7L, 100L, "activity:read");
    verify(detailCache, never()).put(any());
  }

  @Test
  void publicationRequiresAtLeastOneActivePosition() {
    Activity activity = validDraft();
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    when(positionMapper.selectActiveByActivityForUpdate(100L, 1L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.publish(7L, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).status().value())
        .isEqualTo(422);
    verify(activityMapper, never()).updateById(org.mockito.ArgumentMatchers.<Activity>any());
  }

  @Test
  void validDraftCanBePublished() {
    Activity activity = validDraft();
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    when(positionMapper.selectActiveByActivityForUpdate(100L, 1L))
        .thenReturn(List.of(activePosition()));
    when(activityQuestionMapper.selectIdsByActivityForUpdate(1L))
        .thenReturn(Collections.nCopies(4, 1L));
    when(positionQuestionMapper.selectPositionIdsByActivityForUpdate(1L))
        .thenReturn(Collections.nCopies(6, 30L));

    Activity result = service.publish(7L, 1L);

    assertThat(result.getStatus()).isEqualTo("PUBLISHED");
    assertThat(result.getPublishedAt())
        .isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    verify(authorization).requirePermission(7L, 100L, "activity:publish");
    verify(activityMapper).updateById(activity);
    var order =
        inOrder(activityMapper, positionMapper, activityQuestionMapper, positionQuestionMapper);
    order.verify(activityMapper).selectByIdForUpdate(1L);
    order.verify(positionMapper).selectActiveByActivityForUpdate(100L, 1L);
    order.verify(activityQuestionMapper).selectIdsByActivityForUpdate(1L);
    order.verify(positionQuestionMapper).selectPositionIdsByActivityForUpdate(1L);
    order.verify(activityMapper).updateById(activity);
  }

  @Test
  void publicationRejectsAnyPositionWithMoreThanTenCombinedQuestions() {
    Activity activity = validDraft();
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    when(positionMapper.selectActiveByActivityForUpdate(100L, 1L))
        .thenReturn(List.of(activePosition()));
    when(activityQuestionMapper.selectIdsByActivityForUpdate(1L))
        .thenReturn(Collections.nCopies(6, 1L));
    when(positionQuestionMapper.selectPositionIdsByActivityForUpdate(1L))
        .thenReturn(Collections.nCopies(5, 30L));

    assertThatThrownBy(() -> service.publish(7L, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("TOO_MANY_REGISTRATION_QUESTIONS");
    verify(activityMapper, never()).updateById(activity);
  }

  @Test
  void positionServiceTimeMustBeProvidedAsAValidPair() {
    Activity activity = validDraft();
    when(activityMapper.selectById(1L)).thenReturn(activity);
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    var request =
        new ActivityService.PositionRequest(
            "礼仪岗", "负责接待", 10, "FIRST_COME", 120, LocalDateTime.of(2026, 9, 17, 1, 0), null, null);

    assertThatThrownBy(() -> service.addPosition(7L, 1L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo("INVALID_POSITION_TIME");

    verify(positionMapper, never()).insert(org.mockito.ArgumentMatchers.<ActivityPosition>any());
  }

  @Test
  void cancellationCapturesEligibleRecipientsBeforeFinalStateAndNotifiesEachUserOnce() {
    Activity activity = validDraft();
    activity.setTitle("迎新志愿服务");
    activity.setStatus("PUBLISHED");
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    when(activityMapper.cancelCurrent(eq(1L), eq(100L), any())).thenReturn(1);
    when(cycleMapper.selectAffectedUserIdsForActivityCancellation(100L, 1L))
        .thenReturn(List.of(21L, 22L, 21L));
    notifications.beforeWrite(
        () -> {
          assertThat(activity.getStatus()).isEqualTo("CANCELED");
          verify(activityMapper).cancelCurrent(1L, 100L, LocalDateTime.now(clock));
        });

    Activity result = service.cancel(7L, 1L, "天气原因");

    assertThat(result.getStatus()).isEqualTo("CANCELED");
    assertThat(notifications.commands())
        .containsExactly(
            new NotificationCommand(
                100L,
                21L,
                "ACTIVITY_CANCELED",
                "活动已取消",
                "迎新志愿服务已取消：天气原因",
                "ACTIVITY",
                1L,
                "activity:1:canceled"),
            new NotificationCommand(
                100L,
                22L,
                "ACTIVITY_CANCELED",
                "活动已取消",
                "迎新志愿服务已取消：天气原因",
                "ACTIVITY",
                1L,
                "activity:1:canceled"));
    var order = inOrder(cycleMapper, activityMapper, authorization);
    order.verify(activityMapper).selectByIdForUpdate(1L);
    order.verify(authorization).requirePermission(7L, 100L, "activity:cancel");
    order.verify(cycleMapper).selectAffectedUserIdsForActivityCancellation(100L, 1L);
    order.verify(activityMapper).cancelCurrent(1L, 100L, LocalDateTime.now(clock));
    verify(activityMapper, never()).updateById(any(Activity.class));
  }

  @Test
  void zeroRowCancellationUpdateFailsBeforeAuditAndNotification() {
    Activity activity = validDraft();
    activity.setTitle("迎新志愿服务");
    activity.setStatus("PUBLISHED");
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(activity);
    when(cycleMapper.selectAffectedUserIdsForActivityCancellation(100L, 1L))
        .thenReturn(List.of(21L));
    when(activityMapper.cancelCurrent(eq(1L), eq(100L), any())).thenReturn(0);

    assertThatThrownBy(() -> service.cancel(7L, 1L, "天气原因"))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verifyNoInteractions(auditService);
    assertThat(notifications.commands()).isEmpty();
  }

  private Activity validDraft() {
    Activity activity = new Activity();
    activity.setId(1L);
    activity.setOrganizationId(100L);
    activity.setStatus("DRAFT");
    activity.setRegistrationStartAt(LocalDateTime.of(2026, 9, 15, 1, 0));
    activity.setRegistrationEndAt(LocalDateTime.of(2026, 9, 16, 0, 0));
    activity.setActivityStartAt(LocalDateTime.of(2026, 9, 17, 0, 0));
    activity.setActivityEndAt(LocalDateTime.of(2026, 9, 17, 4, 0));
    return activity;
  }

  @Test
  void publicationRejectsAlreadyPublishedStateFromTheSharedLockWithoutReadingQuestions() {
    Activity published = validDraft();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(1L)).thenReturn(validDraft());
    when(activityMapper.selectByIdForUpdate(1L)).thenReturn(published);

    assertThatThrownBy(() -> service.publish(7L, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("ACTIVITY_NOT_DRAFT");
    verifyNoInteractions(
        positionMapper, activityQuestionMapper, positionQuestionMapper, auditService);
    verify(activityMapper, never()).updateById(any(Activity.class));
  }

  private ActivityPosition activePosition() {
    ActivityPosition position = new ActivityPosition();
    position.setId(30L);
    position.setActivityId(1L);
    position.setOrganizationId(100L);
    position.setStatus("ACTIVE");
    return position;
  }

  private ActivityService.PositionRequest validPositionRequest() {
    return new ActivityService.PositionRequest(
        "礼仪岗",
        "负责接待",
        10,
        "FIRST_COME",
        120,
        LocalDateTime.of(2026, 9, 17, 1, 0),
        LocalDateTime.of(2026, 9, 17, 3, 0),
        "东门");
  }
}
