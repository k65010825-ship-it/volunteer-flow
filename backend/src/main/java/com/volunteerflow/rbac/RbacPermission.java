package com.volunteerflow.rbac;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

@TableName("rbac_permission")
@Getter
@Setter
public class RbacPermission {
  @TableId(type = IdType.INPUT)
  private Long id;

  private String code;
  private String name;
  private String description;
  private String status;
}
