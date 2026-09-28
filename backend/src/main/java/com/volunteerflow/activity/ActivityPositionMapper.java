package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityPositionMapper extends BaseMapper<ActivityPosition> {
  @Select(
      "SELECT COUNT(*) FROM activity_position WHERE organization_id=#{orgId} AND"
          + " activity_id=#{activityId} AND status='ACTIVE'")
  Long countActiveByActivity(@Param("orgId") Long orgId, @Param("activityId") Long activityId);

  @Select(
      "SELECT * FROM activity_position WHERE organization_id=#{orgId} AND activity_id=#{activityId}"
          + " AND status='ACTIVE' ORDER BY id")
  List<ActivityPosition> selectActiveByActivity(
      @Param("orgId") Long orgId, @Param("activityId") Long activityId);

  /** Management reads may include unpublished additions; public detail must stay ACTIVE-only. */
  @Select(
      "SELECT * FROM activity_position WHERE organization_id=#{orgId} AND activity_id=#{activityId}"
          + " AND status IN ('ACTIVE','DRAFT') ORDER BY id")
  List<ActivityPosition> selectManageableByActivity(
      @Param("orgId") Long orgId, @Param("activityId") Long activityId);

  @Select("SELECT * FROM activity_position WHERE id=#{positionId} LIMIT 1 FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  ActivityPosition selectByIdForUpdate(@Param("positionId") Long positionId);

  @Select(
      "SELECT * FROM activity_position WHERE organization_id=#{orgId}"
          + " AND activity_id=#{activityId} AND status='ACTIVE' ORDER BY id FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  List<ActivityPosition> selectActiveByActivityForUpdate(
      @Param("orgId") Long orgId, @Param("activityId") Long activityId);

  @Update(
      """
      <script>
      UPDATE activity_position
      <set>
        <if test="changes.containsKey('name')">name=#{changes.name},</if>
        <if test="changes.containsKey('description')">description=#{changes.description},</if>
        <if test="changes.containsKey('capacity')">capacity=#{changes.capacity},</if>
        <if test="changes.containsKey('promotionTimeoutMinutes')">
          promotion_timeout_minutes=#{changes.promotionTimeoutMinutes},
        </if>
        <if test="changes.containsKey('serviceStartAt')">
          service_start_at=#{changes.serviceStartAt,jdbcType=TIMESTAMP},
        </if>
        <if test="changes.containsKey('serviceEndAt')">
          service_end_at=#{changes.serviceEndAt,jdbcType=TIMESTAMP},
        </if>
        <if test="changes.containsKey('meetingLocation')">
          meeting_location=#{changes.meetingLocation,jdbcType=VARCHAR},
        </if>
        version=version+1
      </set>
      WHERE id=#{positionId}
        AND organization_id=#{organizationId}
        AND activity_id=#{activityId}
        AND status='ACTIVE'
      </script>
      """)
  int updatePublishedFields(
      @Param("positionId") Long positionId,
      @Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId,
      @Param("changes") Map<String, Object> changes);

  @Update(
      "UPDATE activity_position SET status='ACTIVE', version=version+1"
          + " WHERE id=#{positionId} AND organization_id=#{organizationId}"
          + " AND activity_id=#{activityId} AND status='DRAFT'")
  int activateDraft(
      @Param("positionId") Long positionId,
      @Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId);
}
