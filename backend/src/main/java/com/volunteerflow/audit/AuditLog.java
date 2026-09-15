package com.volunteerflow.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@TableName("audit_log")
@Data
public class AuditLog {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long actorUserId;
  private String action;
  private String resourceType;
  private Long resourceId;
  private String beforeJson;
  private String afterJson;
  private String reason;
}
