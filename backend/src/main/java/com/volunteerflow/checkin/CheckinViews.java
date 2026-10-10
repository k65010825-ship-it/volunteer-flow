package com.volunteerflow.checkin;

import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Explicit projections: no entity, code digest, or persisted secret is an HTTP response. */
public final class CheckinViews {
  private CheckinViews() {}

  public record CurrentCheckin(SessionView session, OwnAttendance attendance) {}

  public record OwnAttendance(Long registrationId, Long registrationCycleId, Long positionId,
      String positionName, String attendanceStatus, Long recordId, LocalDateTime checkedInAt) {}

  /** Rate is a fraction in [0,1], rounded to four decimal places; empty cohorts return 0. */
  public record AttendanceSummary(Long activityId, String activityStatus, boolean finalStatistics,
      Long confirmedCount, Long checkedInCount, Long noShowCount, Long pendingCount,
      BigDecimal checkinRate, List<PositionAttendance> positions) {}

  public record PositionAttendance(Long positionId, String positionName, Long confirmedCount,
      Long checkedInCount, Long noShowCount, Long pendingCount, BigDecimal checkinRate) {}

  // Internal SQL projections, never returned directly by a controller.
  public record AttendanceCounts(Long confirmedCount, Long checkedInCount, Long noShowCount, Long pendingCount) {}
  public record PositionCounts(Long positionId, String positionName, Long confirmedCount,
      Long checkedInCount, Long noShowCount, Long pendingCount) {}
  public record SessionReference(Long activityId, Long organizationId) {}
  public record RegistrationTarget(Long registrationCycleId, String status) {}
}
