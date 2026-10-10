package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationCycle;
import com.volunteerflow.registration.RegistrationCycleMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CheckinService {
  private final ActivityMapper activities;
  private final CheckinSessionMapper sessions;
  private final OrganizationMemberMapper members;
  private final RegistrationCycleMapper cycles;
  private final CheckinRecordMapper records;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final CheckinCodeGenerator codes;
  private final Clock clock;

  public CheckinService(ActivityMapper activities, CheckinSessionMapper sessions,
      OrganizationMemberMapper members, RegistrationCycleMapper cycles, CheckinRecordMapper records,
      OrganizationAuthorizationService authorization, AuditService audit, CheckinCodeGenerator codes, Clock clock) {
    this.activities = activities; this.sessions = sessions; this.members = members;
    this.cycles = cycles; this.records = records; this.authorization = authorization;
    this.audit = audit; this.codes = codes; this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CheckinResult selfCheckin(Long userId, Long sessionId, CheckinRequest request) {
    if (request == null || request.activityId() == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CHECKIN_REQUEST", "Activity is required");
    }
    // Routing read is not a lock; re-read the session after acquiring its parent activity lock.
    CheckinSession routing = sessions.selectById(sessionId);
    if (routing == null || !Objects.equals(routing.getActivityId(), request.activityId())) throw sessionNotFound();
    Activity activity = lockActivity(request.activityId());
    CheckinSession session = lockSession(sessionId, activity);
    requireMember(userId, activity.getOrganizationId());
    RegistrationCycle cycle = requiredCycle(activity, userId);
    requireNoRecord(activity, cycle);
    // Sample after all blocking reads: a queued request must not retain a stale valid time.
    LocalDateTime now = now();
    if ("FINISHED".equals(activity.getStatus()) || "CANCELED".equals(activity.getStatus())
        || !activity.getActivityEndAt().isAfter(now)) {
      throw error(HttpStatus.CONFLICT, "ACTIVITY_FINAL", "Activity is already final");
    }
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw error(HttpStatus.CONFLICT, "ACTIVITY_CHECKIN_NOT_ALLOWED", "Activity is not published");
    }
    if (!"OPEN".equals(session.getStatus()) || session.getOpensAt().isAfter(now)
        || !session.getClosesAt().isAfter(now)) {
      throw error(HttpStatus.CONFLICT, "CHECKIN_SESSION_NOT_OPEN", "Check-in session is not effective");
    }
    if (!"PENDING".equals(cycle.getAttendanceStatus())) {
      throw error(HttpStatus.CONFLICT, "ATTENDANCE_NOT_PENDING", "Attendance is not pending");
    }
    byte[] expected;
    try {
      expected = HexFormat.of().parseHex(session.getStaticCodeHash());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new IllegalStateException("Check-in digest storage is invalid");
    }
    if (expected.length != 32) throw new IllegalStateException("Check-in digest storage is invalid");
    if (!MessageDigest.isEqual(expected, codes.digest(codes.normalize(request.code())))) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CHECKIN_CODE", "Check-in code is invalid");
    }
    return create(activity, session, cycle, userId, "SELF_SERVICE", null, now);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CheckinResult manualCheckin(Long actorId, Long sessionId, ManualCheckinRequest request) {
    if (request == null || request.registrationId() == null || request.registrationCycleId() == null) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MANUAL_CHECKIN_REQUEST", "Registration and cycle are required");
    }
    String reason = request.reason() == null ? null : request.reason().trim();
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MANUAL_CHECKIN_REASON", "A reason of 1 to 500 characters is required");
    }
    // Both routing reads are non-locking; no cycle lock precedes the activity/session/membership locks.
    CheckinSession routing = sessions.selectById(sessionId);
    if (routing == null) throw sessionNotFound();
    Activity activity = lockActivity(routing.getActivityId());
    CheckinSession session = lockSession(sessionId, activity);
    requireMember(actorId, activity.getOrganizationId());
    authorization.requirePermission(actorId, activity.getOrganizationId(), "checkin:manage");
    RegistrationCycle target = cycles.selectById(request.registrationCycleId());
    if (target == null || !Objects.equals(target.getRegistrationId(), request.registrationId())
        || !Objects.equals(target.getActivityId(), activity.getId())
        || !Objects.equals(target.getOrganizationId(), activity.getOrganizationId())) {
      throw error(HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "Registration was not found");
    }
    RegistrationCycle cycle = requiredCycle(activity, target.getUserId());
    if (!Objects.equals(cycle.getId(), request.registrationCycleId())
        || !Objects.equals(cycle.getRegistrationId(), request.registrationId())) {
      throw error(HttpStatus.CONFLICT, "REGISTRATION_NOT_CONFIRMED", "Requested cycle is not the effective confirmed registration");
    }
    requireNoRecord(activity, cycle);
    if (!"PENDING".equals(cycle.getAttendanceStatus()) && !"NO_SHOW".equals(cycle.getAttendanceStatus())) {
      throw error(HttpStatus.CONFLICT, "ATTENDANCE_NOT_PENDING", "Attendance cannot be corrected");
    }
    // Closed/expired sessions and ended activities intentionally remain eligible for manager correction.
    return create(activity, session, cycle, actorId, "MANUAL", reason, now());
  }

  private Activity lockActivity(Long activityId) {
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) throw error(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    return activity;
  }

  private CheckinSession lockSession(Long sessionId, Activity activity) {
    CheckinSession session = sessions.selectByIdForUpdate(sessionId, activity.getId());
    if (session == null || !Objects.equals(session.getActivityId(), activity.getId())
        || !Objects.equals(session.getOrganizationId(), activity.getOrganizationId())) throw sessionNotFound();
    return session;
  }

  private void requireMember(Long userId, Long organizationId) {
    if (members.selectActiveMemberForUpdate(userId, organizationId) == null) {
      throw error(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
    }
  }

  private RegistrationCycle requiredCycle(Activity activity, Long userId) {
    RegistrationCycle cycle = cycles.selectEffectiveConfirmedForUpdate(activity.getOrganizationId(), activity.getId(), userId);
    if (cycle == null || !Objects.equals(cycle.getOrganizationId(), activity.getOrganizationId())
        || !Objects.equals(cycle.getActivityId(), activity.getId()) || !Objects.equals(cycle.getUserId(), userId)
        || !"CONFIRMED".equals(cycle.getStatus())) {
      throw error(HttpStatus.CONFLICT, "REGISTRATION_NOT_CONFIRMED", "No effective confirmed registration");
    }
    return cycle;
  }

  private void requireNoRecord(Activity activity, RegistrationCycle cycle) {
    if (records.selectByActivityAndUserForUpdate(activity.getId(), cycle.getUserId()) != null
        || "CHECKED_IN".equals(cycle.getAttendanceStatus())) throw alreadyCheckedIn();
  }

  private CheckinResult create(Activity activity, CheckinSession session, RegistrationCycle cycle,
      Long actorId, String method, String reason, LocalDateTime now) {
    CheckinRecord row = new CheckinRecord();
    row.setId(IdWorker.getId()); row.setOrganizationId(activity.getOrganizationId()); row.setActivityId(activity.getId());
    row.setCheckinSessionId(session.getId()); row.setRegistrationCycleId(cycle.getId()); row.setUserId(cycle.getUserId());
    row.setCheckinMethod(method); row.setCheckedInAt(now); row.setStatus("VALID");
    if ("MANUAL".equals(method)) { row.setOperatorId(actorId); row.setManualReason(reason); }
    try {
      if (records.insert(row) != 1) throw new OptimisticLockingFailureException("Check-in record insert affected no row");
    } catch (DuplicateKeyException exception) {
      if (isActivityUserDuplicate(exception)) throw alreadyCheckedIn();
      throw exception;
    }
    if (cycles.markCheckedIn(cycle.getId(), activity.getOrganizationId(), activity.getId(), cycle.getUserId(), cycle.getVersion()) != 1) {
      throw new OptimisticLockingFailureException("Check-in attendance transition affected no row");
    }
    // Never pass the submitted code, digest, session, or request into audit.
    // recordChange is the existing audit operation that enforces a one-row insert.
    audit.recordChange(activity.getOrganizationId(), actorId,
        "MANUAL".equals(method) ? "checkin.manual.created" : "checkin.self.created", "checkin_record", row.getId(),
        Map.of(), Map.of("activityId", activity.getId(), "registrationCycleId", cycle.getId(), "method", method), reason);
    return new CheckinResult(row.getId(), activity.getId(), cycle.getUserId(), method, now);
  }

  private boolean isActivityUserDuplicate(DuplicateKeyException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())) {
        String message = sql.getMessage();
        // MySQL names this key either unqualified or table-qualified. Match the key, not arbitrary SQL text.
        return message != null && (message.contains("for key 'uk_checkin_record_activity_user'")
            || message.contains("for key 'checkin_record.uk_checkin_record_activity_user'"));
      }
    }
    return false;
  }

  private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
  private BusinessException sessionNotFound() { return error(HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND", "Check-in session was not found"); }
  private BusinessException alreadyCheckedIn() { return error(HttpStatus.CONFLICT, "ALREADY_CHECKED_IN", "Already checked in"); }
  private BusinessException error(HttpStatus status, String code, String message) { return new BusinessException(status, code, message); }

  public record CheckinRequest(@NotNull Long activityId, @NotBlank String code) {
    @Override public String toString() { return "CheckinRequest[activityId=" + activityId + ", code=[REDACTED]]"; }
  }
  public record ManualCheckinRequest(@NotNull Long registrationId, @NotNull Long registrationCycleId, @NotBlank String reason) {}
  public record CheckinResult(Long recordId, Long activityId, Long userId, String method, LocalDateTime checkedInAt) {}
}
