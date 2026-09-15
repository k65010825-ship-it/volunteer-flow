package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ActivityQuestionMapper extends BaseMapper<ActivityQuestion> {
  @Select(
      "SELECT * FROM activity_question WHERE organization_id=#{organizationId} AND"
          + " activity_id=#{activityId} ORDER BY sort_order,id")
  List<ActivityQuestion> selectByActivity(
      @Param("organizationId") Long organizationId, @Param("activityId") Long activityId);

  @Select("SELECT COUNT(*) FROM activity_question WHERE activity_id=#{activityId}")
  long countByActivity(@Param("activityId") Long activityId);
}
