package com.volunteerflow.registration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("promotion_offer")
public class PromotionOffer {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private Long positionId;
  private Long registrationCycleId;
  private String status;
  private LocalDateTime expiresAt;
  private LocalDateTime respondedAt;
  private Long createdBy;
  private String reason;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
