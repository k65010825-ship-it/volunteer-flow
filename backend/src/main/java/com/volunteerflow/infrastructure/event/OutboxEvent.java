package com.volunteerflow.infrastructure.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("outbox_event")
public class OutboxEvent {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private String eventId;
  private String aggregateType;
  private String aggregateId;
  private String eventType;
  private String payloadJson;
  private String status;
  private Integer retryCount;
  private LocalDateTime nextRetryAt;
  private LocalDateTime publishedAt;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
  private String topic;
  private String messageKey;
  private String lockedBy;
  private LocalDateTime lockedUntil;
  private String lastError;
  private Integer publishedPartition;
  private Long publishedOffset;
}
