package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("activity")
public class Activity {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private String title;
  private String description;
  private String location;
  private LocalDateTime registrationStartAt;
  private LocalDateTime registrationEndAt;
  private LocalDateTime freeCancelDeadlineAt;
  private LocalDateTime activityStartAt;
  private LocalDateTime activityEndAt;
  private String status;
  private Long createdBy;
  private LocalDateTime publishedAt;
  private LocalDateTime canceledAt;
  private Integer version;
}
