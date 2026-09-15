package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("activity_position")
@Getter
@Setter
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
