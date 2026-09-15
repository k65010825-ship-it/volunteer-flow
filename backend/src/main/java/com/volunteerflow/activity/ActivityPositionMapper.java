package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
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
}
