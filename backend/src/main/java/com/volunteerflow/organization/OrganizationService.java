package com.volunteerflow.organization;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.rbac.RbacPermission;
import com.volunteerflow.rbac.RbacPermissionMapper;
import com.volunteerflow.rbac.RbacRole;
import com.volunteerflow.rbac.RbacRoleMapper;
import com.volunteerflow.rbac.RbacRolePermission;
import com.volunteerflow.rbac.RbacRolePermissionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles organization membership and organization-scoped RBAC changes.
 *
 * <p>Database IDs are logical references rather than physical foreign keys. The service therefore
 * validates ownership and status explicitly before changing related records.
 */
@Service
@Profile("!test")
public class OrganizationService {
  // Built-in roles provide a safe initial permission set; organizations can add custom roles later.
  private static final Set<String> ADMIN_PERMISSIONS =
      Set.of(
          "organization:read",
          "member:read",
          "member:invite",
          "role:read",
          "activity:read",
          "activity:create",
          "activity:publish",
          "activity:change",
          "activity:cancel",
          "registration:review",
          "registration:promote",
          "checkin:manage",
          "audit:read");
  private static final Set<String> MEMBER_PERMISSIONS =
      Set.of(
          "organization:read",
          "activity:read",
          "registration:create",
          "registration:cancel",
          "checkin:create",
          "notification:read");
  private final OrganizationMapper organizations;
  private final OrganizationInviteMapper invites;
  private final OrganizationMemberMapper members;
  private final AppUserMapper users;
  private final RbacRoleMapper roles;
  private final RbacPermissionMapper permissions;
  private final RbacRolePermissionMapper grants;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final com.volunteerflow.auth.RefreshTokenGenerator tokenGenerator;
  private final Clock clock;

  public OrganizationService(
      OrganizationMapper organizations,
      OrganizationInviteMapper invites,
      OrganizationMemberMapper members,
      AppUserMapper users,
      RbacRoleMapper roles,
      RbacPermissionMapper permissions,
      RbacRolePermissionMapper grants,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      com.volunteerflow.auth.RefreshTokenGenerator tokenGenerator,
      Clock clock) {
    this.organizations = organizations;
    this.invites = invites;
    this.members = members;
    this.users = users;
    this.roles = roles;
    this.permissions = permissions;
    this.grants = grants;
    this.authorization = authorization;
    this.audit = audit;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  @Transactional
  public Organization create(
      Long actorId, String actorPlatformRole, CreateOrganizationRequest request) {
    if (!"PLATFORM_ADMIN".equals(actorPlatformRole))
      throw new BusinessException(
          HttpStatus.FORBIDDEN,
          "PLATFORM_ADMIN_REQUIRED",
          "Platform administrator permission is required");
    AppUser ownerUser = users.selectById(request.ownerUserId());
    if (ownerUser == null || !"ACTIVE".equals(ownerUser.getStatus()))
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY, "OWNER_NOT_FOUND", "Active owner user was not found");
    Organization org = new Organization();
    org.setId(IdWorker.getId());
    org.setName(request.name().trim());
    org.setDescription(trim(request.description()));
    org.setStatus("ACTIVE");
    org.setCreatedBy(actorId);
    org.setVersion(0);
    organizations.insert(org);

    // Every organization starts with one protected owner and two usable built-in roles.
    RbacRole owner = createBuiltIn(org.getId(), "组织负责人", "OWNER", true);
    RbacRole admin = createBuiltIn(org.getId(), "活动管理员", "ACTIVITY_ADMIN", false);
    RbacRole member = createBuiltIn(org.getId(), "普通成员", "MEMBER", false);
    grant(admin, ADMIN_PERMISSIONS);
    grant(member, MEMBER_PERMISSIONS);
    OrganizationMember ownerMember = new OrganizationMember();
    ownerMember.setId(IdWorker.getId());
    ownerMember.setOrganizationId(org.getId());
    ownerMember.setUserId(request.ownerUserId());
    ownerMember.setRoleId(owner.getId());
    ownerMember.setStatus("ACTIVE");
    ownerMember.setJoinedAt(now());
    ownerMember.setVersion(0);
    members.insert(ownerMember);
    audit.record(org.getId(), actorId, "organization.created", "organization", org.getId(), null);
    return org;
  }

  public List<Organization> list(Long userId, String platformRole) {
    return "PLATFORM_ADMIN".equals(platformRole)
        ? organizations.selectList(null)
        : organizations.selectVisibleByUser(userId);
  }

  public Organization get(Long userId, String platformRole, Long orgId) {
    Organization org = organizations.selectById(orgId);
    if (org == null || !"ACTIVE".equals(org.getStatus())) throw notFound();
    if (!"PLATFORM_ADMIN".equals(platformRole)) authorization.requireMembership(userId, orgId);
    return org;
  }

  @Transactional
  public void deactivateOrganization(Long actorId, String platformRole, Long orgId) {
    if (!"PLATFORM_ADMIN".equals(platformRole))
      throw new BusinessException(
          HttpStatus.FORBIDDEN,
          "PLATFORM_ADMIN_REQUIRED",
          "Platform administrator permission is required");
    Organization org = organizations.selectById(orgId);
    if (org == null) throw notFound();
    if (!"ACTIVE".equals(org.getStatus())) return;
    org.setStatus("INACTIVE");
    organizations.updateById(org);
    audit.record(orgId, actorId, "organization.deactivated", "organization", orgId, null);
  }

  @Transactional
  public InviteCode createInvite(Long actorId, Long orgId, CreateInviteRequest request) {
    authorization.requirePermission(actorId, orgId, "member:invite");
    RbacRole member = roles.selectBuiltIn(orgId, "MEMBER");
    if (member == null) throw new IllegalStateException("Built-in member role is missing");
    String raw = tokenGenerator.generate();
    Instant expiry =
        request.expiresAt() == null
            ? clock.instant().plus(Duration.ofDays(7))
            : request.expiresAt();
    int max = request.maxUses() == null ? 50 : request.maxUses();
    OrganizationInvite invite = new OrganizationInvite();
    invite.setId(IdWorker.getId());
    invite.setOrganizationId(orgId);
    invite.setDefaultRoleId(member.getId());
    invite.setCodeHash(hashInvite(raw));
    invite.setMaxUses(max);
    invite.setUsedCount(0);
    invite.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC));
    invite.setStatus("ACTIVE");
    invite.setCreatedBy(actorId);
    invite.setVersion(0);
    invites.insert(invite);
    audit.record(
        orgId, actorId, "organization.invite.created", "organization_invite", invite.getId(), null);
    return new InviteCode(raw, expiry, max);
  }

  @Transactional
  public OrganizationMember joinByInvite(Long userId, String raw) {
    if (raw == null || raw.isBlank()) throw invalidInvite();

    // Locking the invite row makes capacity checks and used-count updates one atomic operation.
    OrganizationInvite invite = invites.selectByCodeHashForUpdate(hashInvite(raw));
    if (invite == null
        || !"ACTIVE".equals(invite.getStatus())
        || !invite.getExpiresAt().isAfter(now())
        || invite.getUsedCount() >= invite.getMaxUses()) throw invalidInvite();
    OrganizationMember existing =
        members.selectByOrganizationAndUser(invite.getOrganizationId(), userId);
    if (existing != null) return existing;
    OrganizationMember member = new OrganizationMember();
    member.setId(IdWorker.getId());
    member.setOrganizationId(invite.getOrganizationId());
    member.setUserId(userId);
    member.setRoleId(invite.getDefaultRoleId());
    member.setStatus("ACTIVE");
    member.setJoinedAt(now());
    member.setVersion(0);
    members.insert(member);
    invite.setUsedCount(invite.getUsedCount() + 1);
    if (invite.getUsedCount() >= invite.getMaxUses()) invite.setStatus("EXHAUSTED");
    invites.updateById(invite);
    audit.record(
        invite.getOrganizationId(),
        userId,
        "organization.joined",
        "organization_member",
        member.getId(),
        null);
    return member;
  }

  @Transactional
  public void deactivateInvite(Long actorId, Long orgId, Long inviteId) {
    authorization.requirePermission(actorId, orgId, "member:invite");
    OrganizationInvite invite = invites.selectById(inviteId);
    if (invite == null || !orgId.equals(invite.getOrganizationId())) throw notFound();
    if (!"ACTIVE".equals(invite.getStatus())) return;
    invite.setStatus("INACTIVE");
    invites.updateById(invite);
    audit.record(
        orgId, actorId, "organization.invite.deactivated", "organization_invite", inviteId, null);
  }

  public List<OrganizationMember> members(Long actorId, Long orgId) {
    authorization.requirePermission(actorId, orgId, "member:read");
    return members.selectActiveByOrganization(orgId);
  }

  public List<RbacRole> roles(Long actorId, Long orgId) {
    authorization.requirePermission(actorId, orgId, "role:read");
    return roles.selectActiveByOrganization(orgId);
  }

  public List<RbacPermission> permissions(Long actorId, Long orgId) {
    authorization.requirePermission(actorId, orgId, "role:read");
    return permissions.selectAllActive();
  }

  @Transactional
  public RbacRole createRole(Long actorId, Long orgId, CreateRoleRequest request) {
    authorization.requirePermission(actorId, orgId, "role:create");
    RbacRole role = new RbacRole();
    role.setId(IdWorker.getId());
    role.setOrganizationId(orgId);
    role.setName(request.name().trim());
    role.setDescription(trim(request.description()));
    role.setBuiltInType("CUSTOM");
    role.setProtectedRole(false);
    role.setStatus("ACTIVE");
    role.setVersion(0);
    roles.insert(role);
    setPermissionsInternal(orgId, role, request.permissionCodes());
    audit.record(orgId, actorId, "role.created", "rbac_role", role.getId(), null);
    return role;
  }

  @Transactional
  public void setRolePermissions(
      Long actorId, Long orgId, Long roleId, SetRolePermissionsRequest request) {
    authorization.requirePermission(actorId, orgId, "role:update");
    RbacRole role = roles.selectById(roleId);
    requireRole(orgId, role);
    if (Boolean.TRUE.equals(role.getProtectedRole()))
      throw new BusinessException(
          HttpStatus.CONFLICT, "PROTECTED_ROLE", "Protected owner permissions cannot be reduced");
    setPermissionsInternal(orgId, role, request.permissionCodes());
    audit.record(orgId, actorId, "role.permissions.updated", "rbac_role", roleId, null);
  }

  @Transactional
  public RbacRole updateRole(Long actorId, Long orgId, Long roleId, UpdateRoleRequest request) {
    authorization.requirePermission(actorId, orgId, "role:update");
    RbacRole role = roles.selectById(roleId);
    requireRole(orgId, role);
    if (Boolean.TRUE.equals(role.getProtectedRole())) throw protectedRole();
    role.setName(request.name().trim());
    role.setDescription(trim(request.description()));
    roles.updateById(role);
    audit.record(orgId, actorId, "role.updated", "rbac_role", roleId, null);
    return role;
  }

  @Transactional
  public void deactivateRole(Long actorId, Long orgId, Long roleId) {
    authorization.requirePermission(actorId, orgId, "role:update");
    RbacRole role = roles.selectById(roleId);
    requireRole(orgId, role);
    if (Boolean.TRUE.equals(role.getProtectedRole())) throw protectedRole();
    if (members.countActiveByRole(orgId, roleId) > 0)
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "ROLE_IN_USE",
          "Move active members to another role before deactivation");
    role.setStatus("INACTIVE");
    roles.updateById(role);
    audit.record(orgId, actorId, "role.deactivated", "rbac_role", roleId, null);
  }

  @Transactional
  public void deleteRole(Long actorId, Long orgId, Long roleId) {
    authorization.requirePermission(actorId, orgId, "role:update");
    RbacRole role = roles.selectById(roleId);
    if (role == null || !orgId.equals(role.getOrganizationId())) throw notFound();
    if (Boolean.TRUE.equals(role.getProtectedRole()) || !"CUSTOM".equals(role.getBuiltInType()))
      throw protectedRole();
    if (members.countByRole(orgId, roleId) > 0)
      throw new BusinessException(
          HttpStatus.CONFLICT, "ROLE_IN_USE", "A role referenced by members cannot be deleted");
    role.setStatus("DELETED");
    roles.updateById(role);
    audit.record(orgId, actorId, "role.deleted", "rbac_role", roleId, null);
  }

  @Transactional
  public OrganizationMember assignRole(
      Long actorId, Long orgId, Long memberId, AssignRoleRequest request) {
    authorization.requirePermission(actorId, orgId, "member:role_assign");

    // Serialize owner-role changes so concurrent requests cannot both remove the last owner.
    organizations.selectByIdForUpdate(orgId);
    OrganizationMember member = members.selectById(memberId);
    if (member == null
        || !orgId.equals(member.getOrganizationId())
        || !"ACTIVE".equals(member.getStatus())) throw notFound();
    RbacRole role = roles.selectById(request.roleId());
    requireRole(orgId, role);
    RbacRole currentRole = roles.selectById(member.getRoleId());
    if (currentRole != null
        && "OWNER".equals(currentRole.getBuiltInType())
        && !"OWNER".equals(role.getBuiltInType())
        && members.countActiveByRole(orgId, currentRole.getId()) <= 1)
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "LAST_OWNER_REQUIRED",
          "Organization must retain at least one active owner");
    member.setRoleId(role.getId());
    members.updateById(member);
    audit.record(orgId, actorId, "member.role.assigned", "organization_member", memberId, null);
    return member;
  }

  private RbacRole createBuiltIn(Long orgId, String name, String type, boolean protectedRole) {
    RbacRole role = new RbacRole();
    role.setId(IdWorker.getId());
    role.setOrganizationId(orgId);
    role.setName(name);
    role.setBuiltInType(type);
    role.setProtectedRole(protectedRole);
    role.setStatus("ACTIVE");
    role.setVersion(0);
    roles.insert(role);
    return role;
  }

  private void grant(RbacRole role, Set<String> codes) {
    setPermissionsInternal(role.getOrganizationId(), role, codes);
  }

  private void setPermissionsInternal(Long orgId, RbacRole role, Collection<String> codes) {
    grants.deleteForRole(orgId, role.getId());
    if (codes == null || codes.isEmpty()) return;
    List<RbacPermission> found = permissions.selectByCodes(codes);
    if (found.size() != new HashSet<>(codes).size())
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVALID_PERMISSION",
          "One or more permission codes are invalid");
    for (RbacPermission permission : found) {
      RbacRolePermission grant = new RbacRolePermission();
      grant.setId(IdWorker.getId());
      grant.setOrganizationId(orgId);
      grant.setRoleId(role.getId());
      grant.setPermissionId(permission.getId());
      grants.insert(grant);
    }
  }

  private void requireRole(Long orgId, RbacRole role) {
    if (role == null
        || !orgId.equals(role.getOrganizationId())
        || !"ACTIVE".equals(role.getStatus())) throw notFound();
  }

  private BusinessException protectedRole() {
    return new BusinessException(
        HttpStatus.CONFLICT, "PROTECTED_ROLE", "Protected owner role cannot be changed");
  }

  static String hashInvite(String raw) {
    try {
      // Store only a digest so a database leak does not expose still-valid invitation codes.
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private String trim(String v) {
    return v == null ? null : v.trim();
  }

  private BusinessException notFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND,
        "ORGANIZATION_RESOURCE_NOT_FOUND",
        "Organization resource was not found");
  }

  private BusinessException invalidInvite() {
    return new BusinessException(
        HttpStatus.CONFLICT, "INVALID_INVITE", "Invite code is invalid, expired, or exhausted");
  }

  public record CreateOrganizationRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
          String name,
      @jakarta.validation.constraints.Size(max = 1000) String description,
      @jakarta.validation.constraints.NotNull Long ownerUserId) {}

  public record CreateRoleRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String name,
      @jakarta.validation.constraints.Size(max = 255) String description,
      @jakarta.validation.constraints.NotNull Set<String> permissionCodes) {}

  public record SetRolePermissionsRequest(
      @jakarta.validation.constraints.NotNull Set<String> permissionCodes) {}

  public record UpdateRoleRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String name,
      @jakarta.validation.constraints.Size(max = 255) String description) {}

  public record AssignRoleRequest(@jakarta.validation.constraints.NotNull Long roleId) {}
}
