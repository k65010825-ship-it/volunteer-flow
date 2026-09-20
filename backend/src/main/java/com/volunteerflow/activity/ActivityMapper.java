package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
  /** Shared serialization point for publication and draft question mutations. */
  @Select("SELECT * FROM activity WHERE id=#{id} FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  Activity selectByIdForUpdate(@Param("id") Long id);

  @Select(
      "SELECT * FROM activity WHERE organization_id=#{orgId} AND status='PUBLISHED' ORDER BY"
          + " activity_start_at,id")
  List<Activity> selectPublished(@Param("orgId") Long orgId);

  @Select("SELECT * FROM activity WHERE id=#{id} AND organization_id=#{orgId} LIMIT 1")
  Activity selectInOrganization(@Param("orgId") Long orgId, @Param("id") Long id);

  @Select("SELECT * FROM activity WHERE id=#{id} LIMIT 1")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  Activity selectByIdForSubmissionRevalidation(@Param("id") Long id);

  @Update(
      """
      <script>
      UPDATE activity
      <set>
        <if test="changes.containsKey('title')">title=#{changes.title},</if>
        <if test="changes.containsKey('description')">description=#{changes.description},</if>
        <if test="changes.containsKey('location')">location=#{changes.location},</if>
        <if test="changes.containsKey('registrationEndAt')">
          registration_end_at=#{changes.registrationEndAt},
        </if>
        <if test="changes.containsKey('freeCancelDeadlineAt')">
          free_cancel_deadline_at=#{changes.freeCancelDeadlineAt,jdbcType=TIMESTAMP},
        </if>
        <if test="changes.containsKey('activityStartAt')">
          activity_start_at=#{changes.activityStartAt},
        </if>
        <if test="changes.containsKey('activityEndAt')">
          activity_end_at=#{changes.activityEndAt},
        </if>
        version=version+1
      </set>
      WHERE id=#{id}
        AND organization_id=#{organizationId}
        AND status='PUBLISHED'
      </script>
      """)
  int updatePublishedFields(
      @Param("id") Long id,
      @Param("organizationId") Long organizationId,
      @Param("changes") Map<String, Object> changes);
}
