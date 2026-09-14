package com.volunteerflow.audit;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service
@Profile("!test")
public class AuditService {
    private final AuditLogMapper mapper;
    public AuditService(AuditLogMapper mapper) { this.mapper = mapper; }
    public void record(Long orgId, Long actorId, String action, String type, Long resourceId, String reason) {
        AuditLog log = new AuditLog(); log.setId(IdWorker.getId()); log.setOrganizationId(orgId);
        log.setActorUserId(actorId); log.setAction(action); log.setResourceType(type);
        log.setResourceId(resourceId); log.setReason(reason); mapper.insert(log);
    }
}
