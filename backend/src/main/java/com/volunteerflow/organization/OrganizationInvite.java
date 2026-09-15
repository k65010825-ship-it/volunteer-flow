package com.volunteerflow.organization;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("organization_invite")
@Data
public class OrganizationInvite {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long defaultRoleId;
  private String codeHash;
  private Integer maxUses;
  private Integer usedCount;
  private LocalDateTime expiresAt;
  private String status;
  private Long createdBy;
  private Integer version;
}
