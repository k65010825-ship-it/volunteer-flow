package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@TableName("rbac_role")
@Data
public class RbacRole {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private String name;
  private String description;
  private String builtInType;
  private Boolean protectedRole;
  private String status;
  private Integer version;
}
