package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.event.CheckinSessionStatusPayload;
import com.volunteerflow.infrastructure.event.DomainEventCommand;
import com.volunteerflow.infrastructure.event.DomainEventPublisher;
import com.volunteerflow.infrastructure.event.DomainEventTypes;
import com.volunteerflow.infrastructure.event.NotificationEventTemplate;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CheckinSessionService {
  private final ActivityMapper activities;
  private final CheckinSessionMapper sessions;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final DomainEventPublisher events;
  private final CheckinCodeGenerator codes;
  private final DynamicCheckinTokenInvalidator dynamicTokens;
  private final Clock clock;

  public CheckinSessionService(
      ActivityMapper activities,
      CheckinSessionMapper sessions,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      DomainEventPublisher events,
      CheckinCodeGenerator codes,
      DynamicCheckinTokenInvalidator dynamicTokens,
      Clock clock) {
    this.activities = activities;
    this.sessions = sessions;
    this.authorization = authorization;
    this.audit = audit;
    this.events = events;
    this.codes = codes;
    this.dynamicTokens = dynamicTokens;
    this.clock = clock;
  }

  @Transactional
  public OpenedSession open(Long actorId, Long activityId, OpenSessionRequest request) {
    // The database activity row serializes competing opens, including the first session.
    Activity activity = requiredActivity(activities.selectByIdForUpdate(activityId));
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    // Sample time after the row lock: a lock wait must not preserve an already-expired deadline.
    LocalDateTime now = now();
    if ("CANCELED".equals(activity.getStatus())
        || "FINISHED".equals(activity.getStatus())
        || !activity.getActivityEndAt().isAfter(now)) {
      throw new BusinessException(HttpStatus.CONFLICT, "ACTIVITY_FINAL", "Activity is already final");
    }
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw new BusinessException(HttpStatus.CONFLICT, "ACTIVITY_CHECKIN_NOT_ALLOWED", "Activity is not published");
    }
    int minutes = request == null || request.durationMinutes() == null ? 15 : request.durationMinutes();
    if (minutes < 5 || minutes > 120) {
      throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CHECKIN_DURATION", "Check-in duration must be between 5 and 120 minutes");
    }
    sessions.expireOpen(activityId, now);
    if (sessions.selectEffectiveOpenForUpdate(activityId, now) != null) {
      throw new BusinessException(HttpStatus.CONFLICT, "CHECKIN_SESSION_ALREADY_OPEN", "An effective check-in session is already open");
    }

    CheckinSession session = new CheckinSession();
    session.setId(IdWorker.getId());
    session.setOrganizationId(activity.getOrganizationId());
    session.setActivityId(activityId);
    String sessionType = normalizeSessionType(request == null ? null : request.sessionType());
    session.setSessionType(sessionType);
    session.setStatus("OPEN");
    session.setOpensAt(now);
    session.setClosesAt(now.plusMinutes(minutes));
    session.setCreatedBy(actorId);
    session.setVersion(0);
    // Plaintext never enters an entity, audit argument, notification, or subsequent query DTO.
    String code = "STATIC_CODE".equals(sessionType) ? codes.generate() : null;
    int inserted = code == null ? sessions.insert(session)
        : sessions.insertWithDigest(session, codes.digest(codes.normalize(code)));
    if (inserted != 1) {
      throw new OptimisticLockingFailureException("Check-in session insert affected no row");
    }
    audit.record(activity.getOrganizationId(), actorId, "checkin.session.opened", "checkin_session", session.getId(), null);
    events.publishMany(
        sessions.selectConfirmedUserIds(activity.getOrganizationId(), activityId),
        new NotificationEventTemplate(
            activity.getOrganizationId(), activityId, "CHECKIN_SESSION", session.getId().toString(),
            DomainEventTypes.CHECKIN_OPENED,
            "CHECKIN_OPENED",
            "活动签到已开启",
            activity.getTitle() + ("DYNAMIC_QR".equals(sessionType)
                ? "签到已开启，请扫描现场二维码；有效期至 "
                : "签到已开启，请向现场管理员获取签到码；有效期至 ") + session.getClosesAt(),
            "ACTIVITY",
            activityId));
    return new OpenedSession(session.getId(), activityId, session.getOpensAt(), session.getClosesAt(), sessionType, code);
  }

  @Transactional
  public SessionView close(Long actorId, Long sessionId) {
    // Routing read takes no session lock. Re-read under activity -> session locks before mutation.
    CheckinSession routing = sessions.selectById(sessionId);
    if (routing == null) throw sessionNotFound();
    Activity activity = requiredActivity(activities.selectByIdForUpdate(routing.getActivityId()));
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    CheckinSession session = sessions.selectByIdForUpdate(sessionId, activity.getId());
    if (session == null || !Objects.equals(session.getOrganizationId(), activity.getOrganizationId())) {
      throw sessionNotFound();
    }
    if (!"OPEN".equals(session.getStatus())) return SessionView.from(session);
    Instant closeInstant = clock.instant();
    LocalDateTime now = LocalDateTime.ofInstant(closeInstant, ZoneOffset.UTC);
    String status = session.getClosesAt().isAfter(now) ? "CLOSED" : "EXPIRED";
    if (sessions.closeOpen(sessionId, activity.getId(), status, now) != 1) {
      throw new OptimisticLockingFailureException("Check-in session closure affected no row");
    }
    session.setStatus(status);
    if ("CLOSED".equals(status)) session.setClosesAt(now);
    session.setVersion(session.getVersion() + 1);
    audit.record(activity.getOrganizationId(), actorId, "checkin.session.closed", "checkin_session", sessionId, null);
    events.publish(new DomainEventCommand(activity.getOrganizationId(), activity.getId(),
        "CHECKIN_SESSION", sessionId.toString(), DomainEventTypes.CHECKIN_CLOSED,
        new CheckinSessionStatusPayload(actorId, sessionId, status)));
    if ("DYNAMIC_QR".equals(session.getSessionType())) {
      dynamicTokens.afterCommit(sessionId, closeInstant);
    }
    return SessionView.from(session);
  }

  @Transactional(readOnly = true)
  public SessionView currentForMember(Long userId, Long activityId) {
    Activity activity = requiredActivity(activities.selectById(activityId));
    authorization.requireMembership(userId, activity.getOrganizationId());
    return current(activity);
  }

  @Transactional(readOnly = true)
  public SessionView currentForManager(Long actorId, Long activityId) {
    Activity activity = requiredActivity(activities.selectById(activityId));
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    return current(activity);
  }

  private SessionView current(Activity activity) {
    LocalDateTime now = now();
    if (!"PUBLISHED".equals(activity.getStatus()) || !activity.getActivityEndAt().isAfter(now)) return null;
    CheckinSession session = sessions.selectEffectiveOpen(activity.getId(), now);
    return session == null ? null : SessionView.from(session);
  }

  private Activity requiredActivity(Activity activity) {
    if (activity == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    return activity;
  }

  private BusinessException sessionNotFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND", "Check-in session was not found");
  }

  private String normalizeSessionType(String value) {
    if (value == null) return "STATIC_CODE";
    if ("STATIC_CODE".equals(value) || "DYNAMIC_QR".equals(value)) return value;
    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_CHECKIN_SESSION_TYPE", "Unsupported check-in session type");
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  public record OpenSessionRequest(@Min(5) @Max(120) Integer durationMinutes, String sessionType) {
    public OpenSessionRequest(Integer durationMinutes) { this(durationMinutes, null); }
  }

  public record OpenedSession(Long sessionId, Long activityId, LocalDateTime opensAt,
      LocalDateTime closesAt, String sessionType, String code) {
    public OpenedSession(Long sessionId, Long activityId, LocalDateTime opensAt,
        LocalDateTime closesAt, String code) {
      this(sessionId, activityId, opensAt, closesAt, "STATIC_CODE", code);
    }
    @Override
    public String toString() {
      return "OpenedSession[sessionId=" + sessionId + ", activityId=" + activityId
          + ", opensAt=" + opensAt + ", closesAt=" + closesAt + ", sessionType="
          + sessionType + ", code=[REDACTED]]";
    }
  }

  public record SessionView(Long sessionId, Long activityId, String sessionType, String status, LocalDateTime opensAt, LocalDateTime closesAt) {
    private static SessionView from(CheckinSession session) {
      return new SessionView(session.getId(), session.getActivityId(), session.getSessionType(), session.getStatus(), session.getOpensAt(), session.getClosesAt());
    }
  }
}
