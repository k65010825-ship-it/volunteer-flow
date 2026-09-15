package com.volunteerflow.audit;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class AuditService {
  private final AuditLogMapper mapper;

  public AuditService(AuditLogMapper mapper) {
    this.mapper = mapper;
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
}
