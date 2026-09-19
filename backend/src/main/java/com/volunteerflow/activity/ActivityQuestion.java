package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("activity_question")
public class ActivityQuestion {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private String questionType;
  private String title;
  private Boolean requiredQuestion;
  private String optionsJson;
  private Integer sortOrder;
}
