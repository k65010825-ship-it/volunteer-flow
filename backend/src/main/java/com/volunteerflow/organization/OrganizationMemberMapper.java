package com.volunteerflow.organization;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*; import java.util.List;
@Mapper public interface OrganizationMemberMapper extends BaseMapper<OrganizationMember> {
 @Select("SELECT * FROM organization_member WHERE organization_id=#{orgId} AND user_id=#{userId} LIMIT 1") OrganizationMember selectByOrganizationAndUser(@Param("orgId") Long orgId,@Param("userId") Long userId);
 @Select("SELECT * FROM organization_member WHERE organization_id=#{orgId} AND user_id=#{userId} AND status='ACTIVE' LIMIT 1") OrganizationMember selectActiveMember(@Param("userId") Long userId,@Param("orgId") Long orgId);
 @Select("SELECT * FROM organization_member WHERE organization_id=#{orgId} AND status='ACTIVE' ORDER BY joined_at") List<OrganizationMember> selectActiveByOrganization(@Param("orgId") Long orgId);
}
