package com.volunteerflow.organization;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("organization_member")
@Getter
@Setter
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
