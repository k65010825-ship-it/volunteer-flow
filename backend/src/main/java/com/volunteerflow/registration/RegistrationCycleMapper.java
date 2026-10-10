package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RegistrationCycleMapper extends BaseMapper<RegistrationCycle> {
  @Select("""
      SELECT COUNT(*) FROM registration_cycle c FORCE INDEX (idx_cycle_activity_status_id)
      LEFT JOIN registration_cycle newer
        ON newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId}
        AND c.status='CONFIRMED' AND newer.id IS NULL
      """)
  @Options(useCache = false)
  long countCurrentConfirmedForActivity(@Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId);

  /** Join the exact cycle, tenant, activity and member; invalid/old-cycle records cannot grant attendance. */
  @Update("""
      UPDATE registration_cycle c FORCE INDEX (idx_cycle_activity_status_id)
      LEFT JOIN registration_cycle newer
        ON newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
      LEFT JOIN checkin_record r ON r.activity_id=c.activity_id AND r.user_id=c.user_id
        AND r.organization_id=c.organization_id AND r.registration_cycle_id=c.id AND r.status='VALID'
      SET c.attendance_status=CASE WHEN r.id IS NOT NULL THEN 'CHECKED_IN' ELSE 'NO_SHOW' END,
          c.version=c.version+1
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId}
        AND c.status='CONFIRMED' AND newer.id IS NULL
      """)
  int finalizeCurrentConfirmedAttendance(@Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId);

  /** Called only after activity/session and membership locks. Never resurrect an old cycle. */
  @Select("""
      SELECT c.* FROM registration_cycle c
      WHERE c.organization_id=#{organizationId} AND c.activity_id=#{activityId}
        AND c.user_id=#{userId} AND c.status='CONFIRMED'
        AND NOT EXISTS (
          SELECT 1 FROM registration_cycle newer
          WHERE newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number
        )
      ORDER BY c.cycle_number DESC,c.id DESC LIMIT 1 FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  RegistrationCycle selectEffectiveConfirmedForUpdate(
      @Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId,
      @Param("userId") Long userId);

  @Update("""
      UPDATE registration_cycle SET attendance_status='CHECKED_IN',version=version+1
      WHERE id=#{cycleId} AND organization_id=#{organizationId} AND activity_id=#{activityId}
        AND user_id=#{userId} AND status='CONFIRMED' AND version=#{version}
        AND attendance_status IN ('PENDING','NO_SHOW')
      """)
  int markCheckedIn(
      @Param("cycleId") Long cycleId,
      @Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId,
      @Param("userId") Long userId,
      @Param("version") Integer version);

  @Select("SELECT * FROM registration_cycle WHERE id=#{cycleId} FOR UPDATE")
  RegistrationCycle selectByIdForUpdate(@Param("cycleId") Long cycleId);

  // A cycle can receive only one invitation, including an expired invitation awaiting cleanup.
  @Select(
      """
      SELECT c.* FROM registration_cycle c
      WHERE c.position_id=#{positionId} AND c.status='WAITLISTED'
        AND c.waitlist_sequence IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM promotion_offer o WHERE o.registration_cycle_id=c.id)
      ORDER BY c.waitlist_sequence ASC LIMIT 1 FOR UPDATE
      """)
  RegistrationCycle selectFirstWaitlistedForUpdate(@Param("positionId") Long positionId);

  @Select(
      """
      SELECT * FROM registration_cycle
      WHERE registration_id=#{registrationId}
        AND status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED')
      ORDER BY cycle_number DESC LIMIT 1 FOR UPDATE
      """)
  RegistrationCycle selectActiveCycleForUpdate(@Param("registrationId") Long registrationId);

  @Select(
      "SELECT COUNT(*) FROM registration_cycle WHERE position_id=#{positionId} AND"
          + " status='CONFIRMED'")
  long countConfirmed(@Param("positionId") Long positionId);

  @Select(
      """
      SELECT COUNT(*) FROM registration_cycle
      WHERE position_id=#{positionId} AND status='WAITLISTED' AND waitlist_sequence < #{sequence}
      """)
  long countActiveWaitlistBefore(
      @Param("positionId") Long positionId, @Param("sequence") long sequence);

  @Select(
      "SELECT COUNT(*) FROM registration_cycle WHERE position_id=#{positionId} AND"
          + " status='WAITLISTED'")
  long countActiveWaitlisted(@Param("positionId") Long positionId);

  @Select(
      """
      SELECT DISTINCT c.user_id
      FROM registration_cycle c
      WHERE c.organization_id=#{organizationId}
        AND c.activity_id=#{activityId}
        AND (
          c.status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED')
          OR EXISTS (
            SELECT 1
            FROM promotion_offer o
            WHERE o.registration_cycle_id=c.id
              AND o.organization_id=c.organization_id
              AND o.activity_id=c.activity_id
              AND o.status='PENDING'
              AND o.expires_at > CURRENT_TIMESTAMP(6)
          )
        )
        AND NOT EXISTS (
          SELECT 1 FROM promotion_offer o
          WHERE c.status='WAITLISTED' AND o.registration_cycle_id=c.id
            AND o.organization_id=c.organization_id AND o.activity_id=c.activity_id
            AND (o.status<>'PENDING' OR o.expires_at <= CURRENT_TIMESTAMP(6))
        )
      ORDER BY c.user_id
      """)
  List<Long> selectAffectedUserIdsForActivityCancellation(
      @Param("organizationId") Long organizationId, @Param("activityId") Long activityId);

  @Select(
      """
      SELECT DISTINCT c.user_id
      FROM registration_cycle c
      WHERE c.activity_id=#{activityId}
        AND (
          c.status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED')
          OR EXISTS (
            SELECT 1
            FROM promotion_offer o
            WHERE o.registration_cycle_id=c.id
              AND o.organization_id=c.organization_id
              AND o.activity_id=c.activity_id
              AND o.status='PENDING'
              AND o.expires_at > #{now}
          )
        )
        AND NOT EXISTS (
          SELECT 1 FROM promotion_offer o
          WHERE c.status='WAITLISTED' AND o.registration_cycle_id=c.id
            AND o.organization_id=c.organization_id AND o.activity_id=c.activity_id
            AND (o.status<>'PENDING' OR o.expires_at <= #{now})
        )
      ORDER BY c.user_id
      """)
  List<Long> selectAffectedUserIds(
      @Param("activityId") Long activityId, @Param("now") LocalDateTime now);

  @Select(
      """
      SELECT * FROM registration_cycle
      WHERE registration_id=#{registrationId}
      ORDER BY CASE WHEN status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED') THEN 0 ELSE 1 END,
               cycle_number DESC
      LIMIT 1
      """)
  RegistrationCycle selectActiveOrLatest(@Param("registrationId") Long registrationId);

  @Select(
      """
      <script>
      SELECT ranked.* FROM (
        SELECT c.*,
               ROW_NUMBER() OVER (
                 PARTITION BY c.registration_id
                 ORDER BY CASE
                   WHEN c.status IN ('PENDING_REVIEW', 'CONFIRMED', 'WAITLISTED') THEN 0 ELSE 1
                 END,
                 c.cycle_number DESC
               ) AS row_rank
        FROM registration_cycle c
        WHERE c.user_id=#{userId} AND c.registration_id IN
        <foreach collection="registrationIds" item="registrationId"
                 open="(" separator="," close=")">#{registrationId}</foreach>
      ) ranked
      WHERE ranked.row_rank=1
      </script>
      """)
  List<RegistrationCycle> selectActiveOrLatestOwned(
      @Param("userId") Long userId, @Param("registrationIds") List<Long> registrationIds);

  @Select(
      """
      <script>
      SELECT c.id AS cycle_id,
             CASE WHEN c.status='WAITLISTED' AND c.waitlist_sequence IS NOT NULL THEN
               (SELECT COUNT(*) + 1 FROM registration_cycle earlier_cycle
                WHERE earlier_cycle.organization_id=c.organization_id
                  AND earlier_cycle.position_id=c.position_id
                  AND earlier_cycle.status='WAITLISTED'
                  AND earlier_cycle.waitlist_sequence IS NOT NULL
                  AND earlier_cycle.waitlist_sequence &lt; c.waitlist_sequence)
               ELSE NULL END AS current_waitlist_position,
             CASE WHEN c.status='WAITLISTED' THEN
               (SELECT COUNT(*) FROM registration_cycle active_cycle
                WHERE active_cycle.organization_id=c.organization_id
                  AND active_cycle.position_id=c.position_id
                  AND active_cycle.status='WAITLISTED')
               ELSE 0 END AS waitlist_count
      FROM registration_cycle c
      WHERE c.user_id=#{userId} AND c.id IN
      <foreach collection="cycleIds" item="cycleId"
               open="(" separator="," close=")">#{cycleId}</foreach>
      </script>
      """)
  @ConstructorArgs({
    @Arg(column = "cycle_id", javaType = Long.class),
    @Arg(column = "current_waitlist_position", javaType = Long.class),
    @Arg(column = "waitlist_count", javaType = Long.class)
  })
  List<WaitlistMetrics> selectWaitlistMetricsOwned(
      @Param("userId") Long userId, @Param("cycleIds") List<Long> cycleIds);

  @Select(
      """
      <script>
      SELECT * FROM registration_cycle
      WHERE organization_id=#{organizationId} AND position_id=#{positionId}
      <if test="status != null">AND status=#{status}</if>
      ORDER BY submitted_at DESC, id DESC
      </script>
      """)
  IPage<RegistrationCycle> selectForPosition(
      IPage<RegistrationCycle> page,
      @Param("organizationId") Long organizationId,
      @Param("positionId") Long positionId,
      @Param("status") String status);

  record WaitlistMetrics(Long cycleId, Long currentWaitlistPosition, Long waitlistCount) {}
}
