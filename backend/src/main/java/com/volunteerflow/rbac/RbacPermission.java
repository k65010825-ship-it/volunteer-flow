package com.volunteerflow.rbac;
import com.baomidou.mybatisplus.annotation.*;
@TableName("rbac_permission") public class RbacPermission {
 @TableId(type=IdType.INPUT) private Long id; private String code; private String name; private String description; private String status;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getCode(){return code;} public void setCode(String v){code=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;}
}
