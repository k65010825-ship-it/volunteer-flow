package com.volunteerflow.organization;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("organization_invite")
public class OrganizationInvite {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long defaultRoleId;
  private String codeHash;
  private Integer maxUses;
  private Integer usedCount;
  private LocalDateTime expiresAt;
  private String status;
  private Long createdBy;
  private Integer version;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(Long v) {
    organizationId = v;
  }

  public Long getDefaultRoleId() {
    return defaultRoleId;
  }

  public void setDefaultRoleId(Long v) {
    defaultRoleId = v;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public void setCodeHash(String v) {
    codeHash = v;
  }

  public Integer getMaxUses() {
    return maxUses;
  }

  public void setMaxUses(Integer v) {
    maxUses = v;
  }

  public Integer getUsedCount() {
    return usedCount;
  }

  public void setUsedCount(Integer v) {
    usedCount = v;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime v) {
    expiresAt = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public Long getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(Long v) {
    createdBy = v;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer v) {
    version = v;
  }
}
