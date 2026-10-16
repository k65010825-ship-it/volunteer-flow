package com.volunteerflow.checkin;

import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class DynamicCheckinCodeService {
  private final CheckinSessionMapper sessions;
  private final ActivityMapper activities;
  private final OrganizationAuthorizationService authorization;
  private final DynamicCheckinTokenStore tokens;
  private final Clock clock;

  public DynamicCheckinCodeService(CheckinSessionMapper sessions, ActivityMapper activities,
      OrganizationAuthorizationService authorization, DynamicCheckinTokenStore tokens, Clock clock) {
    this.sessions = sessions;
    this.activities = activities;
    this.authorization = authorization;
    this.tokens = tokens;
    this.clock = clock;
  }

  public DynamicCodeView currentCode(Long actorId, Long sessionId) {
    CheckinSession session = sessions.selectById(sessionId);
    if (session == null) throw notFound();
    Activity activity = activities.selectById(session.getActivityId());
    if (activity == null || !Objects.equals(activity.getOrganizationId(), session.getOrganizationId())) {
      throw notFound();
    }
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    Instant now = clock.instant();
    LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    if (!"DYNAMIC_QR".equals(session.getSessionType()) || !"OPEN".equals(session.getStatus())
        || session.getOpensAt().isAfter(utcNow) || !session.getClosesAt().isAfter(utcNow)
        || !"PUBLISHED".equals(activity.getStatus()) || !activity.getActivityEndAt().isAfter(utcNow)) {
      throw new BusinessException(HttpStatus.CONFLICT, "DYNAMIC_CHECKIN_NOT_ALLOWED",
          "Dynamic check-in is not available for this session");
    }
    try {
      var issued = tokens.issue(sessionId, now);
      return new DynamicCodeView(sessionId, activity.getId(), issued.windowEpochSecond(),
          issued.expiresAt(), issued.token());
    } catch (DynamicCheckinTokenStore.StoreUnavailableException exception) {
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DYNAMIC_CHECKIN_UNAVAILABLE",
          "Dynamic check-in is temporarily unavailable");
    }
  }

  private BusinessException notFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND",
        "Check-in session was not found");
  }

  public record DynamicCodeView(Long sessionId, Long activityId, long windowEpochSecond,
      Instant expiresAt, String token) {
    @Override public String toString() {
      return "DynamicCodeView[sessionId=" + sessionId + ", activityId=" + activityId
          + ", windowEpochSecond=" + windowEpochSecond + ", expiresAt=" + expiresAt
          + ", token=[REDACTED]]";
    }
  }
}
