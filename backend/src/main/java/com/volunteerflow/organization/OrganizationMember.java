package com.volunteerflow.organization;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("organization_member")
@Data
public class OrganizationMember {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long userId;
  private Long roleId;
  private String status;
  private LocalDateTime joinedAt;
  private Integer version;
}
