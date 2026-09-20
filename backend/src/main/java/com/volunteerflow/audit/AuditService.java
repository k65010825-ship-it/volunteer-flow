package com.volunteerflow.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class AuditService {
  private final AuditLogMapper mapper;
  private final ObjectMapper objectMapper;

  public AuditService(AuditLogMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  public void record(
      Long organizationId,
      Long actorId,
      String action,
      String resourceType,
      Long resourceId,
      String reason) {
    AuditLog log = new AuditLog();
    log.setId(IdWorker.getId());
    log.setOrganizationId(organizationId);
    log.setActorUserId(actorId);
    log.setAction(action);
    log.setResourceType(resourceType);
    log.setResourceId(resourceId);
    log.setReason(reason);
    mapper.insert(log);
  }

  public Long recordChange(
      Long organizationId,
      Long actorId,
      String action,
      String resourceType,
      Long resourceId,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason) {
    AuditLog log = new AuditLog();
    log.setId(IdWorker.getId());
    log.setOrganizationId(organizationId);
    log.setActorUserId(actorId);
    log.setAction(action);
    log.setResourceType(resourceType);
    log.setResourceId(resourceId);
    log.setBeforeJson(json(before));
    log.setAfterJson(json(after));
    log.setReason(reason);
    if (mapper.insert(log) != 1) {
      throw new OptimisticLockingFailureException("Audit change insert affected no row");
    }
    return log.getId();
  }

  private String json(Map<String, Object> snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize audit snapshot", exception);
    }
  }
}
