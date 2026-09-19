package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityServiceTest {
  private final ActivityMapper activityMapper = mock(ActivityMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper activityQuestionMapper = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper positionQuestionMapper =
      mock(ActivityPositionQuestionMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
  private final ActivityService service =
      new ActivityService(
          activityMapper,
          positionMapper,
          activityQuestionMapper,
          positionQuestionMapper,
          authorization,
          auditService,
          clock);

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
    var request =
        new ActivityService.PositionRequest(
            "礼仪岗", "负责接待", 10, "FIRST_COME", 120, LocalDateTime.of(2026, 9, 17, 1, 0), null, null);

    assertThatThrownBy(() -> service.addPosition(7L, 1L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo("INVALID_POSITION_TIME");

    verify(positionMapper, never()).insert(org.mockito.ArgumentMatchers.<ActivityPosition>any());
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
}
