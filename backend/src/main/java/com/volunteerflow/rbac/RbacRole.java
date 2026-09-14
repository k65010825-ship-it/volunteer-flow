package com.volunteerflow.rbac;
import com.baomidou.mybatisplus.annotation.*;
@TableName("rbac_role") public class RbacRole {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private Long organizationId; private String name; private String description; private String builtInType; private Boolean protectedRole; private String status; private Integer version;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getOrganizationId(){return organizationId;} public void setOrganizationId(Long v){organizationId=v;} public String getName(){return name;} public void setName(String v){name=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public String getBuiltInType(){return builtInType;} public void setBuiltInType(String v){builtInType=v;} public Boolean getProtectedRole(){return protectedRole;} public void setProtectedRole(Boolean v){protectedRole=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
}
