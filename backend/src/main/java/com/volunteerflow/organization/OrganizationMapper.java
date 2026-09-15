package com.volunteerflow.organization;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*; import java.util.List;
@Mapper public interface OrganizationMapper extends BaseMapper<Organization> {
 @Select("SELECT o.* FROM organization o JOIN organization_member m ON m.organization_id=o.id WHERE m.user_id=#{userId} AND m.status='ACTIVE' AND o.status='ACTIVE' ORDER BY o.create_time DESC") List<Organization> selectVisibleByUser(@Param("userId") Long userId);
 @Select("SELECT * FROM organization WHERE id=#{id} LIMIT 1 FOR UPDATE") Organization selectByIdForUpdate(@Param("id") Long id);
}
