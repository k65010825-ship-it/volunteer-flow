package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("activity_position")
@Data
public class ActivityPosition {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private String name;
  private String description;
  private Integer capacity;
  private String registrationMode;
  private Integer promotionTimeoutMinutes;
  private LocalDateTime serviceStartAt;
  private LocalDateTime serviceEndAt;
  private String meetingLocation;
  private Long nextWaitlistSequence;
  private String status;
  private Integer version;
}
