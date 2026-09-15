package com.volunteerflow.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("app_user")
@Getter
@Setter
public class AppUser {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private String username;
  private String passwordHash;
  private String realName;
  private String studentNumber;
  private String contact;
  private String status;
  private String platformRole;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
