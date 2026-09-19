package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityPositionQuestionMapper extends BaseMapper<ActivityPositionQuestion> {
  @Select("SELECT * FROM activity_position_question WHERE id=#{id} FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  ActivityPositionQuestion selectByIdForUpdate(@Param("id") Long id);

  @Select(
      "SELECT position_id FROM activity_position_question WHERE activity_id=#{activityId}"
          + " ORDER BY position_id,id FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  List<Long> selectPositionIdsByActivityForUpdate(@Param("activityId") Long activityId);

  /** Null options must reach the column when a choice question becomes TEXT or BOOLEAN. */
  @Update(
      "UPDATE activity_position_question SET question_type=#{questionType}, title=#{title},"
          + " required_question=#{requiredQuestion}, options_json=#{optionsJson,jdbcType=VARCHAR},"
          + " sort_order=#{sortOrder} WHERE id=#{id} AND position_id=#{positionId}"
          + " AND activity_id=#{activityId} AND organization_id=#{organizationId}")
  int updateDefinition(ActivityPositionQuestion question);

  @Select(
      "SELECT * FROM activity_position_question WHERE organization_id=#{organizationId} AND"
          + " activity_id=#{activityId} AND position_id=#{positionId} ORDER BY sort_order,id")
  List<ActivityPositionQuestion> selectByPosition(
      @Param("organizationId") Long organizationId,
      @Param("activityId") Long activityId,
      @Param("positionId") Long positionId);

  @Select(
      "SELECT COALESCE(MAX(question_count),0) FROM (SELECT COUNT(q.id) AS question_count FROM"
          + " activity_position p LEFT JOIN activity_position_question q ON q.position_id=p.id"
          + " WHERE p.activity_id=#{activityId} AND p.status='ACTIVE' GROUP BY p.id) counts")
  long maxQuestionCountByActivity(@Param("activityId") Long activityId);
}
