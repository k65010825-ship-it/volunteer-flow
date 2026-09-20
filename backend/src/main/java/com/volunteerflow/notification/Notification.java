package com.volunteerflow.notification;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("notification")
public class Notification {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long userId;
  private String notificationType;
  private String title;
  private String content;
  private String businessType;
  private Long businessId;
  private String deduplicationKey;
  private LocalDateTime readAt;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
