package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("activity_position_question")
public class ActivityPositionQuestion {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private Long positionId;
  private String questionType;
  private String title;
  private Boolean requiredQuestion;
  private String optionsJson;
  private Integer sortOrder;
}
