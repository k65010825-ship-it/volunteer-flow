package com.volunteerflow.organization;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("organization")
@Data
public class Organization {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private String name;
  private String description;
  private String status;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
