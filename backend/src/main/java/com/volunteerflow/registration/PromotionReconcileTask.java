package com.volunteerflow.registration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("promotion_reconcile_task")
public class PromotionReconcileTask {
  @TableId(type = IdType.INPUT)
  private Long positionId;

  private Long organizationId;
  private String status;
  private Long requestVersion;
  private Long processedVersion;
  private LocalDateTime nextRunAt;
  private String lockedBy;
  private LocalDateTime lockedUntil;
  private Integer attemptCount;
  private String lastError;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
