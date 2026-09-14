package com.volunteerflow.activity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*; import java.util.List;
@Mapper public interface ActivityMapper extends BaseMapper<Activity>{
 @Select("SELECT * FROM activity WHERE organization_id=#{orgId} AND status='PUBLISHED' ORDER BY activity_start_at,id") List<Activity> selectPublished(@Param("orgId") Long orgId);
 @Select("SELECT * FROM activity WHERE id=#{id} AND organization_id=#{orgId} LIMIT 1") Activity selectInOrganization(@Param("orgId") Long orgId,@Param("id") Long id);
}
