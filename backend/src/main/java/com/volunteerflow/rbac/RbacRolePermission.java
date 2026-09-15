package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@TableName("rbac_role_permission")
@Data
public class RbacRolePermission {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long roleId;
  private Long permissionId;
}
