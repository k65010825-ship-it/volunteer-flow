package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@TableName("rbac_permission")
@Data
public class RbacPermission {
  @TableId(type = IdType.INPUT)
  private Long id;

  private String code;
  private String name;
  private String description;
  private String status;
}
