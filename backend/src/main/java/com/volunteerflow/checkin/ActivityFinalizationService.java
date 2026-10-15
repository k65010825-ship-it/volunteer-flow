package com.volunteerflow.checkin;

import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityDetailCacheInvalidator;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ActivityFinalizationService {
  private final ActivityMapper activities;
  private final RegistrationCycleMapper cycles;
  private final AuditService audit;
  private final ActivityDetailCacheInvalidator cacheInvalidator;

  public ActivityFinalizationService(
      ActivityMapper activities,
      RegistrationCycleMapper cycles,
      AuditService audit,
      ActivityDetailCacheInvalidator cacheInvalidator) {
    this.activities = activities;
    this.cycles = cycles;
    this.audit = audit;
    this.cacheInvalidator = cacheInvalidator;
  }

  /** The conditional claim holds the parent row lock until attendance and audit commit together. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public boolean finalizeOne(Long activityId, LocalDateTime now) {
    int claimed = activities.claimFinished(activityId, now);
    if (claimed == 0) return false;
    if (claimed != 1) throw new OptimisticLockingFailureException("Activity claim affected an unexpected row count");
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) throw new OptimisticLockingFailureException("Claimed activity was not found");

    // No Java cycle load. Both statements scope the V9 index to this activity and current CONFIRMED cycles.
    // A concurrent registration change that changes the matched count aborts rather than silently finishing partial attendance.
    long expected = cycles.countCurrentConfirmedForActivity(activity.getOrganizationId(), activityId);
    int updated = cycles.finalizeCurrentConfirmedAttendance(activity.getOrganizationId(), activityId);
    if (updated != expected) throw new OptimisticLockingFailureException("Attendance finalization row count changed");

    // recordChange, unlike record, requires exactly one audit insert; any failure rolls back the FINISHED claim.
    audit.recordChange(activity.getOrganizationId(), null, "activity.finished", "activity", activityId,
        Map.of("status", "PUBLISHED"), Map.of("status", "FINISHED", "confirmedCount", expected), null);
    cacheInvalidator.evictAfterCommit(activityId);
    return true;
  }
}
