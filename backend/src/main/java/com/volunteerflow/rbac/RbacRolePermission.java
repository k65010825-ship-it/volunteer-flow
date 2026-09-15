package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;

@TableName("rbac_role_permission")
public class RbacRolePermission {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long roleId;
  private Long permissionId;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(Long v) {
    organizationId = v;
  }

  public Long getRoleId() {
    return roleId;
  }

  public void setRoleId(Long v) {
    roleId = v;
  }

  public Long getPermissionId() {
    return permissionId;
  }

  public void setPermissionId(Long v) {
    permissionId = v;
  }
}
