package com.volunteerflow.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OrganizationMemberMapper extends BaseMapper<OrganizationMember> {
  /** For expiry cleanup only; this is not an active-membership authorization check. */
  @Select(
      """
      SELECT * FROM organization_member
      WHERE organization_id=#{orgId} AND user_id=#{userId}
      LIMIT 1 FOR UPDATE
      """)
  OrganizationMember selectMemberForUpdateAnyStatus(
      @Param("userId") Long userId, @Param("orgId") Long orgId);

  @Select(
      "SELECT * FROM organization_member WHERE organization_id=#{orgId} AND user_id=#{userId} LIMIT"
          + " 1")
  OrganizationMember selectByOrganizationAndUser(
      @Param("orgId") Long orgId, @Param("userId") Long userId);

  @Select(
      "SELECT * FROM organization_member WHERE organization_id=#{orgId} AND user_id=#{userId} AND"
          + " status='ACTIVE' LIMIT 1")
  OrganizationMember selectActiveMember(@Param("userId") Long userId, @Param("orgId") Long orgId);

  @Select(
      """
      SELECT * FROM organization_member
      WHERE organization_id=#{orgId} AND user_id=#{userId} AND status='ACTIVE'
      LIMIT 1 FOR UPDATE
      """)
  OrganizationMember selectActiveMemberForUpdate(
      @Param("userId") Long userId, @Param("orgId") Long orgId);

  @Select(
      "SELECT * FROM organization_member WHERE organization_id=#{orgId} AND status='ACTIVE' ORDER"
          + " BY joined_at")
  List<OrganizationMember> selectActiveByOrganization(@Param("orgId") Long orgId);

  @Select(
      "SELECT COUNT(*) FROM organization_member WHERE organization_id=#{orgId} AND"
          + " role_id=#{roleId} AND status='ACTIVE'")
  long countActiveByRole(@Param("orgId") Long orgId, @Param("roleId") Long roleId);

  @Select(
      "SELECT COUNT(*) FROM organization_member WHERE organization_id=#{orgId} AND"
          + " role_id=#{roleId}")
  long countByRole(@Param("orgId") Long orgId, @Param("roleId") Long roleId);
}
