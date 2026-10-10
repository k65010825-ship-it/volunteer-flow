package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("checkin_record")
public class CheckinRecord {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private Long organizationId;
  private Long activityId;
  private Long checkinSessionId;
  private Long registrationCycleId;
  private Long userId;
  private String checkinMethod;
  private LocalDateTime checkedInAt;
  private Long operatorId;
  private String manualReason;
  private String status;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
