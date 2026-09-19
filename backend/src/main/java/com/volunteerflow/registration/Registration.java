package com.volunteerflow.registration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Stable relationship between a member and an activity, retained across attempts. */
@Getter
@Setter
@TableName("registration")
public class Registration {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private Long userId;
  private Integer lastCycleNumber;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
