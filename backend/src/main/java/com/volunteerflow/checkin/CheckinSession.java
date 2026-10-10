package com.volunteerflow.checkin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("checkin_session")
public class CheckinSession {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private String sessionType;
  @JsonIgnore private String staticCodeHash;
  private String status;
  private LocalDateTime opensAt;
  private LocalDateTime closesAt;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
