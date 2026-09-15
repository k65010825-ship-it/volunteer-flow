package com.volunteerflow.rbac;
import com.volunteerflow.infrastructure.web.BusinessException; import com.volunteerflow.organization.*; import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.context.annotation.Profile;
@Service @Profile("!test") public class OrganizationAuthorizationService {
 private final OrganizationMemberMapper members; private final RbacRoleMapper roles; private final RbacRolePermissionMapper grants;
 public OrganizationAuthorizationService(OrganizationMemberMapper m,RbacRoleMapper r,RbacRolePermissionMapper g){members=m;roles=r;grants=g;}
 public void requireMembership(Long userId,Long orgId){if(members.selectActiveMember(userId,orgId)==null) throw new BusinessException(HttpStatus.NOT_FOUND,"ORGANIZATION_NOT_FOUND","Organization was not found");}
 public void requirePermission(Long userId,Long orgId,String code){OrganizationMember m=members.selectActiveMember(userId,orgId); if(m==null) throw new BusinessException(HttpStatus.NOT_FOUND,"ORGANIZATION_NOT_FOUND","Organization was not found"); RbacRole r=roles.selectById(m.getRoleId()); if(r==null||!orgId.equals(r.getOrganizationId())||!"ACTIVE".equals(r.getStatus())) throw forbidden(); if(Boolean.TRUE.equals(r.getProtectedRole())&&"OWNER".equals(r.getBuiltInType())) return; if(grants.countActivePermission(orgId,r.getId(),code)==0) throw forbidden();}
 private BusinessException forbidden(){return new BusinessException(HttpStatus.FORBIDDEN,"PERMISSION_DENIED","You do not have permission for this operation");}
}
