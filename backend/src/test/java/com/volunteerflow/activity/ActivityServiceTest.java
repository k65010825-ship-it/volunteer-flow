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
import org.junit.jupiter.api.Test;

class ActivityServiceTest {
  private final ActivityMapper activityMapper = mock(ActivityMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService auditService = mock(AuditService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
  private final ActivityService service =
      new ActivityService(activityMapper, positionMapper, authorization, auditService, clock);

  @Test
  void publicationRequiresAtLeastOneActivePosition() {
    Activity activity = validDraft();
    when(activityMapper.selectById(1L)).thenReturn(activity);
    when(positionMapper.countActiveByActivity(100L, 1L)).thenReturn(0L);

    assertThatThrownBy(() -> service.publish(7L, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).status().value())
        .isEqualTo(422);
    verify(activityMapper, never()).updateById(org.mockito.ArgumentMatchers.<Activity>any());
  }

  @Test
  void validDraftCanBePublished() {
    Activity activity = validDraft();
    when(activityMapper.selectById(1L)).thenReturn(activity);
    when(positionMapper.countActiveByActivity(100L, 1L)).thenReturn(2L);

    Activity result = service.publish(7L, 1L);

    assertThat(result.getStatus()).isEqualTo("PUBLISHED");
    assertThat(result.getPublishedAt())
        .isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    verify(authorization).requirePermission(7L, 100L, "activity:publish");
    verify(activityMapper).updateById(activity);
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
}
