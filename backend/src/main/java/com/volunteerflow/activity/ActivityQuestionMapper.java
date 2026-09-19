package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityQuestionMapper extends BaseMapper<ActivityQuestion> {
  @Select("SELECT * FROM activity_question WHERE id=#{id} FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  ActivityQuestion selectByIdForUpdate(@Param("id") Long id);

  @Select("SELECT id FROM activity_question WHERE activity_id=#{activityId} ORDER BY id FOR UPDATE")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  List<Long> selectIdsByActivityForUpdate(@Param("activityId") Long activityId);

  /** Explicitly assign options_json even when null; do not change global null-update strategy. */
  @Update(
      "UPDATE activity_question SET question_type=#{questionType}, title=#{title},"
          + " required_question=#{requiredQuestion}, options_json=#{optionsJson,jdbcType=VARCHAR},"
          + " sort_order=#{sortOrder} WHERE id=#{id} AND activity_id=#{activityId}"
          + " AND organization_id=#{organizationId}")
  int updateDefinition(ActivityQuestion question);

  @Select(
      "SELECT * FROM activity_question WHERE organization_id=#{organizationId} AND"
          + " activity_id=#{activityId} ORDER BY sort_order,id")
  List<ActivityQuestion> selectByActivity(
      @Param("organizationId") Long organizationId, @Param("activityId") Long activityId);

  @Select("SELECT COUNT(*) FROM activity_question WHERE activity_id=#{activityId}")
  long countByActivity(@Param("activityId") Long activityId);
}
