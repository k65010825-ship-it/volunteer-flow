package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationCycleMapper extends BaseMapper<RegistrationCycle> {
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
}
