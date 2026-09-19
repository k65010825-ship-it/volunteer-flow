package com.volunteerflow.registration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("registration_answer")
public class RegistrationAnswer {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long registrationCycleId;
  private String questionScope;
  private Long questionId;
  private String answerJson;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
