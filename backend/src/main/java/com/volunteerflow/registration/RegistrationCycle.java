package com.volunteerflow.registration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** One registration attempt; subsequent attempts never overwrite this cycle's answers. */
@Getter
@Setter
@TableName("registration_cycle")
public class RegistrationCycle {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long registrationId;
  private Long activityId;
  private Long positionId;
  private Long userId;
  private Integer cycleNumber;
  private String status;
  private Long waitlistSequence;
  private LocalDateTime submittedAt;
  private Long reviewedBy;
  private LocalDateTime reviewedAt;
  private String reviewReason;
  private LocalDateTime canceledAt;
  private String cancelReason;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
