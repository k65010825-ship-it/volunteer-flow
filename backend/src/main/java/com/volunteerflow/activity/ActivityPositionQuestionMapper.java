package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ActivityPositionQuestionMapper extends BaseMapper<ActivityPositionQuestion> {
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
