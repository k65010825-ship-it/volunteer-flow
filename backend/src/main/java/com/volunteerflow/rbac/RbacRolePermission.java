package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

@TableName("rbac_role_permission")
@Getter
@Setter
public class RbacRolePermission {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long roleId;
  private Long permissionId;
}
