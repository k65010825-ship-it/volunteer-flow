package com.volunteerflow.organization;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("organization")
public class Organization {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private String name; private String description; private String status; private Long createdBy; private Integer version; private LocalDateTime createTime; private LocalDateTime updateTime;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public Long getCreatedBy(){return createdBy;} public void setCreatedBy(Long v){createdBy=v;} public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;} public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime v){createTime=v;} public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime v){updateTime=v;}
}
