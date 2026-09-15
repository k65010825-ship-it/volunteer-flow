package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

@TableName("rbac_role")
@Getter
@Setter
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
