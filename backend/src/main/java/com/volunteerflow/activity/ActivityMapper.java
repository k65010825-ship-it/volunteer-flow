package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
  /** Project only cursor keys; the V9 index and LIMIT keep every scheduler read bounded. */
  @Select("""
      <script>
      SELECT id,activity_end_at FROM activity FORCE INDEX (idx_activity_status_end_id)
      WHERE status='PUBLISHED' AND activity_end_at&lt;=#{now}
      <if test="cursorEndAt != null">
        AND (activity_end_at > #{cursorEndAt}
             OR (activity_end_at = #{cursorEndAt} AND id > #{cursorId}))
      </if>
      ORDER BY activity_end_at,id LIMIT #{batchSize}
      </script>
      """)
  @ConstructorArgs({@Arg(column = "id", javaType = Long.class),
      @Arg(column = "activity_end_at", javaType = LocalDateTime.class)})
  @Options(useCache = false)
  List<DueActivityId> selectDueIds(@Param("now") LocalDateTime now,
      @Param("cursorEndAt") LocalDateTime cursorEndAt, @Param("cursorId") Long cursorId,
      @Param("batchSize") int batchSize);

  @Update("""
      UPDATE activity SET status='FINISHED'
      WHERE id=#{id} AND status='PUBLISHED' AND activity_end_at <= #{now}
      """)
  int claimFinished(@Param("id") Long id, @Param("now") LocalDateTime now);

  /** Authorized manual completion is explicit; it never changes the planned end timestamp. */
  @Update("""
      UPDATE activity SET status='FINISHED', version=version+1
      WHERE id=#{id} AND organization_id=#{organizationId} AND status='PUBLISHED'
      """)
  int claimFinishedManually(@Param("id") Long id, @Param("organizationId") Long organizationId);

  record DueActivityId(Long id, LocalDateTime activityEndAt) {}

  /** Shared serialization point for publication and draft question mutations. */
  @Select("SELECT * FROM activity WHERE id=#{id} FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  Activity selectByIdForUpdate(@Param("id") Long id);

  @Update("""
      UPDATE activity SET status='CANCELED', canceled_at=#{canceledAt}, version=version+1
      WHERE id=#{id} AND organization_id=#{organizationId} AND status IN ('DRAFT','PUBLISHED')
      """)
  int cancelCurrent(@Param("id") Long id, @Param("organizationId") Long organizationId,
      @Param("canceledAt") LocalDateTime canceledAt);

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
