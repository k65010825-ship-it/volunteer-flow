package com.volunteerflow.activity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("activity_position")
public class ActivityPosition {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;

  private Long organizationId;
  private Long activityId;
  private String name;
  private String description;
  private Integer capacity;
  private String registrationMode;
  private Integer promotionTimeoutMinutes;
  private LocalDateTime serviceStartAt;
  private LocalDateTime serviceEndAt;
  private String meetingLocation;
  private Long nextWaitlistSequence;
  private String status;
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

  public Long getActivityId() {
    return activityId;
  }

  public void setActivityId(Long v) {
    activityId = v;
  }

  public String getName() {
    return name;
  }

  public void setName(String v) {
    name = v;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String v) {
    description = v;
  }

  public Integer getCapacity() {
    return capacity;
  }

  public void setCapacity(Integer v) {
    capacity = v;
  }

  public String getRegistrationMode() {
    return registrationMode;
  }

  public void setRegistrationMode(String v) {
    registrationMode = v;
  }

  public Integer getPromotionTimeoutMinutes() {
    return promotionTimeoutMinutes;
  }

  public void setPromotionTimeoutMinutes(Integer v) {
    promotionTimeoutMinutes = v;
  }

  public LocalDateTime getServiceStartAt() {
    return serviceStartAt;
  }

  public void setServiceStartAt(LocalDateTime v) {
    serviceStartAt = v;
  }

  public LocalDateTime getServiceEndAt() {
    return serviceEndAt;
  }

  public void setServiceEndAt(LocalDateTime v) {
    serviceEndAt = v;
  }

  public String getMeetingLocation() {
    return meetingLocation;
  }

  public void setMeetingLocation(String v) {
    meetingLocation = v;
  }

  public Long getNextWaitlistSequence() {
    return nextWaitlistSequence;
  }

  public void setNextWaitlistSequence(Long v) {
    nextWaitlistSequence = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer v) {
    version = v;
  }
}
