package com.volunteerflow.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.LocalDateTime;

@TableName("audit_log")
@Getter
@Setter
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

  // Database-generated timestamp; audit writes must not override it.
  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private LocalDateTime createTime;
}
