package com.volunteerflow.organization;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("organization_member")
public class OrganizationMember {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private Long organizationId; private Long userId; private Long roleId; private String status; private LocalDateTime joinedAt; private Integer version;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getOrganizationId(){return organizationId;} public void setOrganizationId(Long v){organizationId=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;} public Long getRoleId(){return roleId;} public void setRoleId(Long v){roleId=v;} public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getJoinedAt(){return joinedAt;} public void setJoinedAt(LocalDateTime v){joinedAt=v;} public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
}
