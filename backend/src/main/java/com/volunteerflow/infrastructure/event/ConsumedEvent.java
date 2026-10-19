package com.volunteerflow.infrastructure.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("consumed_event")
public class ConsumedEvent {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private String consumerName;
  private String eventId;
  private LocalDateTime processedAt;
}
