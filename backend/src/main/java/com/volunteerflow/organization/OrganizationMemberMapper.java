package com.volunteerflow.organization;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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

  /** Authorization requires an active organization as well as an active membership. */
  @Select(
      """
      SELECT m.* FROM organization_member m
      WHERE m.organization_id=#{orgId} AND m.user_id=#{userId} AND m.status='ACTIVE'
      AND EXISTS (SELECT 1 FROM organization o WHERE o.id=m.organization_id AND o.status='ACTIVE')
      LIMIT 1
      """)
  OrganizationMember selectActiveMember(@Param("userId") Long userId, @Param("orgId") Long orgId);

  // The organization subquery is deliberately non-locking: preserve the existing member lock order.
  // Deactivation blocks new requests; already-authorized concurrent transactions may finish.
  @Select(
      """
      SELECT m.* FROM organization_member m
      WHERE m.organization_id=#{orgId} AND m.user_id=#{userId} AND m.status='ACTIVE'
      AND EXISTS (SELECT 1 FROM organization o WHERE o.id=m.organization_id AND o.status='ACTIVE')
      LIMIT 1 FOR UPDATE
      """)
  OrganizationMember selectActiveMemberForUpdate(
      @Param("userId") Long userId, @Param("orgId") Long orgId);

  @Select(
      "SELECT * FROM organization_member WHERE organization_id=#{orgId} AND status='ACTIVE' ORDER"
          + " BY joined_at")
  List<OrganizationMember> selectActiveByOrganization(@Param("orgId") Long orgId);

  @Select("""
      SELECT * FROM organization_member
      WHERE organization_id=#{orgId} AND status='ACTIVE'
      ORDER BY joined_at,id
      """)
  IPage<OrganizationMember> selectActivePage(IPage<OrganizationMember> page, @Param("orgId") Long orgId);

  @Select(
      "SELECT COUNT(*) FROM organization_member WHERE organization_id=#{orgId} AND"
          + " role_id=#{roleId} AND status='ACTIVE'")
  long countActiveByRole(@Param("orgId") Long orgId, @Param("roleId") Long roleId);

  @Select(
      "SELECT COUNT(*) FROM organization_member WHERE organization_id=#{orgId} AND"
          + " role_id=#{roleId}")
  long countByRole(@Param("orgId") Long orgId, @Param("roleId") Long roleId);

  /** Snapshots eligible recipients without locking other members during promotion finalization. */
  @Select(
      """
      SELECT DISTINCT m.user_id FROM organization_member m
      JOIN organization o ON o.id=m.organization_id AND o.status='ACTIVE'
      JOIN app_user u ON u.id=m.user_id AND u.status='ACTIVE'
      JOIN rbac_role r ON r.id=m.role_id AND r.organization_id=m.organization_id AND r.status='ACTIVE'
      WHERE m.organization_id=#{orgId} AND m.status='ACTIVE'
      AND ((r.protected_role=1 AND r.built_in_type='OWNER') OR EXISTS (
        SELECT 1 FROM rbac_role_permission rp
        JOIN rbac_permission p ON p.id=rp.permission_id AND p.status='ACTIVE'
        WHERE rp.organization_id=m.organization_id AND rp.role_id=r.id AND p.code=#{permissionCode}
      ))
      ORDER BY m.user_id
      """)
  List<Long> selectNotificationRecipientIds(
      @Param("orgId") Long orgId, @Param("permissionCode") String permissionCode);
}
