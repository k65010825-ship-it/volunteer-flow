package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicCheckinCodeServiceTest {
  private final CheckinSessionMapper sessions = mock(CheckinSessionMapper.class);
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  private final DynamicCheckinTokenStore tokens = mock(DynamicCheckinTokenStore.class);
  private final Instant now = Instant.parse("2026-10-16T04:00:00Z");
  private final DynamicCheckinCodeService service = new DynamicCheckinCodeService(
      sessions, activities, authorization, tokens, Clock.fixed(now, ZoneOffset.UTC));

  @BeforeEach void arrange() {
    CheckinSession session = new CheckinSession();
    session.setId(101L); session.setActivityId(11L); session.setOrganizationId(10L);
    session.setSessionType("DYNAMIC_QR"); session.setStatus("OPEN");
    session.setOpensAt(LocalDateTime.ofInstant(now.minusSeconds(10), ZoneOffset.UTC));
    session.setClosesAt(LocalDateTime.ofInstant(now.plusSeconds(600), ZoneOffset.UTC));
    when(sessions.selectById(101L)).thenReturn(session);
    Activity activity = new Activity();
    activity.setId(11L); activity.setOrganizationId(10L); activity.setStatus("PUBLISHED");
    activity.setActivityEndAt(LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
    when(activities.selectById(11L)).thenReturn(activity);
    when(tokens.issue(101L, now)).thenReturn(new DynamicCheckinTokenStore.IssuedCredential(
        1_792_137_600L, now.plusSeconds(45), "secret-token"));
  }

  @Test void managerReceivesCurrentDynamicCodeAfterAuthorization() {
    var result = service.currentCode(7L, 101L);
    assertThat(result.sessionId()).isEqualTo(101L);
    assertThat(result.activityId()).isEqualTo(11L);
    assertThat(result.token()).isEqualTo("secret-token");
    verify(authorization).requirePermission(7L, 10L, "checkin:manage");
  }

  @Test void staticSessionIsRejectedBeforeRedis() {
    CheckinSession session = sessions.selectById(101L);
    session.setSessionType("STATIC_CODE");
    assertThatThrownBy(() -> service.currentCode(7L, 101L))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("DYNAMIC_CHECKIN_NOT_ALLOWED"));
    verifyNoInteractions(tokens);
  }

  @Test void redisFailureMapsToServiceUnavailableWithoutToken() {
    when(tokens.issue(101L, now)).thenThrow(
        new DynamicCheckinTokenStore.StoreUnavailableException("down"));
    assertThatThrownBy(() -> service.currentCode(7L, 101L))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.code()).isEqualTo("DYNAMIC_CHECKIN_UNAVAILABLE");
          assertThat(error.status().value()).isEqualTo(503);
        });
  }

  @Test void viewStringRedactsToken() {
    var result = service.currentCode(7L, 101L);
    assertThat(result.toString()).doesNotContain("secret-token");
  }
}
