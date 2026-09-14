package com.volunteerflow.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long organizationId;
    private Long actorUserId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private String beforeJson;
    private String afterJson;
    private String reason;
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public Long getOrganizationId() { return organizationId; } public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getActorUserId() { return actorUserId; } public void setActorUserId(Long v) { this.actorUserId = v; }
    public String getAction() { return action; } public void setAction(String v) { this.action = v; }
    public String getResourceType() { return resourceType; } public void setResourceType(String v) { this.resourceType = v; }
    public Long getResourceId() { return resourceId; } public void setResourceId(Long v) { this.resourceId = v; }
    public String getBeforeJson() { return beforeJson; } public void setBeforeJson(String v) { this.beforeJson = v; }
    public String getAfterJson() { return afterJson; } public void setAfterJson(String v) { this.afterJson = v; }
    public String getReason() { return reason; } public void setReason(String v) { this.reason = v; }
}
