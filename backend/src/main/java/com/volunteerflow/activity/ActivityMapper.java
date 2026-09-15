package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {
  @Select(
      "SELECT * FROM activity WHERE organization_id=#{orgId} AND status='PUBLISHED' ORDER BY"
          + " activity_start_at,id")
  List<Activity> selectPublished(@Param("orgId") Long orgId);

  @Select("SELECT * FROM activity WHERE id=#{id} AND organization_id=#{orgId} LIMIT 1")
  Activity selectInOrganization(@Param("orgId") Long orgId, @Param("id") Long id);

  @Select("SELECT * FROM activity WHERE id=#{id} LIMIT 1")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  Activity selectByIdForSubmissionRevalidation(@Param("id") Long id);
}
