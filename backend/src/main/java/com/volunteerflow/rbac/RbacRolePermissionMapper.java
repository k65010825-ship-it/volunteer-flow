package com.volunteerflow.rbac;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*;
@Mapper public interface RbacRolePermissionMapper extends BaseMapper<RbacRolePermission> {
 @Select("SELECT COUNT(*) FROM rbac_role_permission rp JOIN rbac_permission p ON p.id=rp.permission_id WHERE rp.organization_id=#{orgId} AND rp.role_id=#{roleId} AND p.code=#{code} AND p.status='ACTIVE'") Long countActivePermission(@Param("orgId") Long orgId,@Param("roleId") Long roleId,@Param("code") String code);
 @Delete("DELETE FROM rbac_role_permission WHERE organization_id=#{orgId} AND role_id=#{roleId}") int deleteForRole(@Param("orgId") Long orgId,@Param("roleId") Long roleId);
}
