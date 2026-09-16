package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationCycleMapper extends BaseMapper<RegistrationCycle> {
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
