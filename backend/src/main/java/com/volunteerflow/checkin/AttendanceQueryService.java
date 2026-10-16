package com.volunteerflow.checkin;

import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.checkin.CheckinService.CheckinRequest;
import com.volunteerflow.checkin.CheckinService.ManualCheckinRequest;
import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import com.volunteerflow.checkin.CheckinViews.*;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
@Transactional(readOnly = true)
public class AttendanceQueryService {
  private final ActivityMapper activities;
  private final AttendanceMapper projections;
  private final OrganizationAuthorizationService authorization;
  private final Clock clock;

  public AttendanceQueryService(ActivityMapper activities, AttendanceMapper projections,
      OrganizationAuthorizationService authorization, Clock clock) {
    this.activities = activities; this.projections = projections;
    this.authorization = authorization; this.clock = clock;
  }

  public CurrentCheckin current(Long userId, Long activityId) {
    Activity activity = activity(activityId);
    authorization.requireMembership(userId, activity.getOrganizationId());
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    SessionView session = "PUBLISHED".equals(activity.getStatus()) && activity.getActivityEndAt().isAfter(now)
        ? projections.selectCurrentSession(activity.getOrganizationId(), activityId, now) : null;
    return new CurrentCheckin(session, projections.selectOwnAttendance(activity.getOrganizationId(), activityId, userId));
  }

  public AttendanceSummary summary(Long actorId, Long activityId) {
    Activity activity = activity(activityId);
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    AttendanceCounts counts = projections.selectTotals(activity.getOrganizationId(), activityId);
    var positions = projections.selectPositions(activity.getOrganizationId(), activityId).stream()
        .map(p -> new PositionAttendance(p.positionId(), p.positionName(), p.confirmedCount(),
            p.checkedInCount(), p.noShowCount(), p.pendingCount(), rate(p.checkedInCount(), p.confirmedCount())))
        .toList();
    return new AttendanceSummary(activityId, activity.getStatus(), "FINISHED".equals(activity.getStatus()),
        counts.confirmedCount(), counts.checkedInCount(), counts.noShowCount(), counts.pendingCount(),
        rate(counts.checkedInCount(), counts.confirmedCount()), positions,
        projections.selectCorrectionSession(activity.getOrganizationId(), activityId));
  }

  public CheckinRequest selfRequest(Long userId, Long sessionId, String code) {
    Activity activity = sessionActivity(sessionId);
    authorization.requireMembership(userId, activity.getOrganizationId());
    return new CheckinRequest(activity.getId(), code);
  }

  /** Resolves tenant-scoped routing without allowing the credential into a MySQL DTO. */
  public DynamicCheckinTarget dynamicSelfRequest(Long userId, Long sessionId) {
    Activity activity = sessionActivity(sessionId);
    authorization.requireMembership(userId, activity.getOrganizationId());
    return new DynamicCheckinTarget(activity.getId());
  }

  public ManualCheckinRequest manualRequest(Long actorId, Long sessionId, Long registrationId, String reason) {
    Activity activity = sessionActivity(sessionId);
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    RegistrationTarget target = projections.selectRegistrationTarget(activity.getOrganizationId(), activity.getId(), registrationId);
    if (target == null) throw new BusinessException(HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "Registration was not found");
    if (!"CONFIRMED".equals(target.status())) {
      throw new BusinessException(HttpStatus.CONFLICT, "REGISTRATION_NOT_CONFIRMED", "No effective confirmed registration");
    }
    return new ManualCheckinRequest(registrationId, target.registrationCycleId(), reason);
  }

  private Activity sessionActivity(Long sessionId) {
    SessionReference session = projections.selectSessionReference(sessionId);
    if (session == null) throw sessionNotFound();
    Activity activity = activity(session.activityId());
    if (!Objects.equals(activity.getOrganizationId(), session.organizationId())) throw sessionNotFound();
    return activity;
  }

  private Activity activity(Long activityId) {
    Activity activity = activities.selectById(activityId);
    if (activity == null) throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    return activity;
  }
  private BusinessException sessionNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND", "Check-in session was not found");
  }
  private BigDecimal rate(Long checkedIn, Long confirmed) {
    return confirmed == 0 ? BigDecimal.ZERO
        : BigDecimal.valueOf(checkedIn).divide(BigDecimal.valueOf(confirmed), 4, RoundingMode.HALF_UP);
  }

  public record DynamicCheckinTarget(Long activityId) {}
}
