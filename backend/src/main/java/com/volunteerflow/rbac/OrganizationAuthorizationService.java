package com.volunteerflow.rbac;

import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Centralizes membership checks and organization-scoped RBAC authorization. */
@Service
@Profile("!test")
public class OrganizationAuthorizationService {
  private final OrganizationMemberMapper members;
  private final RbacRoleMapper roles;
  private final RbacRolePermissionMapper grants;

  public OrganizationAuthorizationService(
      OrganizationMemberMapper members, RbacRoleMapper roles, RbacRolePermissionMapper grants) {
    this.members = members;
    this.roles = roles;
    this.grants = grants;
  }

  public void requireMembership(Long userId, Long orgId) {
    // Hide organizations from non-members instead of revealing that a protected resource exists.
    if (members.selectActiveMember(userId, orgId) == null)
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
  }

  public void requirePermission(Long userId, Long orgId, String code) {
    OrganizationMember member = members.selectActiveMember(userId, orgId);
    if (member == null)
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
    RbacRole role = roles.selectById(member.getRoleId());
    if (role == null
        || !orgId.equals(role.getOrganizationId())
        || !"ACTIVE".equals(role.getStatus())) throw forbidden();

    // OWNER is protected so an accidental permission edit cannot lock every manager out.
    if (Boolean.TRUE.equals(role.getProtectedRole()) && "OWNER".equals(role.getBuiltInType()))
      return;
    if (grants.countActivePermission(orgId, role.getId(), code) == 0) throw forbidden();
  }

  private BusinessException forbidden() {
    return new BusinessException(
        HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You do not have permission for this operation");
  }
}
