package com.volunteerflow.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@TableName("refresh_session")
@Getter
@Setter
public class RefreshSession {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long userId;
  private String tokenHash;
  private String deviceName;
  private LocalDateTime expiresAt;
  private LocalDateTime lastUsedAt;
  private LocalDateTime revokedAt;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
