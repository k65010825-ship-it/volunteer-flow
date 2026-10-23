package com.volunteerflow.organization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.auth.SecureTokenGenerator;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrganizationAccessTest {
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final RbacRoleMapper roles = mock(RbacRoleMapper.class);
  private final RbacPermissionMapper permissions = mock(RbacPermissionMapper.class);
  private final RbacRolePermissionMapper grants = mock(RbacRolePermissionMapper.class);
  private final OrganizationService service = new OrganizationService(
      mock(OrganizationMapper.class), mock(OrganizationInviteMapper.class), members,
      mock(AppUserMapper.class), roles, permissions, grants,
      new OrganizationAuthorizationService(members, roles, grants), mock(AuditService.class),
      new SecureTokenGenerator(), Clock.systemUTC());

  @Test
  void ordinaryMemberReceivesOnlyCurrentRoleGrantsWithoutRoleReadRequirement() {
    activeMember();
    when(grants.selectActivePermissionCodes(100L, 30L)).thenReturn(List.of("activity:read"));
    var access = service.access(1L, 100L);
    assertThat(access.organizationId()).isEqualTo(100L);
    assertThat(access.memberId()).isEqualTo(8L);
    assertThat(access.roleId()).isEqualTo(30L);
    assertThat(access.roleName()).isEqualTo("Reader");
    assertThat(access.permissionCodes()).containsExactly("activity:read");
  }

  @Test
  void missingOrInactiveMembershipOrOrganizationIsHiddenEvenForPlatformAdmin() {
    var controller = new OrganizationController(service);
    var admin = new com.volunteerflow.auth.CurrentUser(1L, "admin", null, null, null, "PLATFORM_ADMIN");
    assertThatThrownBy(() -> controller.access(admin, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code()).isEqualTo("ORGANIZATION_NOT_FOUND");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE", "DELETED", "OTHER_ORGANIZATION"})
  void unusableCurrentRoleCannotExposeAccess(String state) {
    activeMember();
    RbacRole role = roles.selectById(30L);
    if (state.equals("MISSING")) when(roles.selectById(30L)).thenReturn(null);
    else if (state.equals("OTHER_ORGANIZATION")) role.setOrganizationId(200L);
    else role.setStatus(state);
    assertThatThrownBy(() -> service.access(1L, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code()).isEqualTo("PERMISSION_DENIED");
  }

  @Test
  void protectedOwnerGetsActiveCatalogWithoutExplicitGrants() {
    activeMember();
    RbacRole owner = roles.selectById(30L);
    owner.setBuiltInType("OWNER");
    owner.setProtectedRole(true);
    RbacPermission permission = new RbacPermission();
    permission.setCode("role:read");
    when(permissions.selectAllActive()).thenReturn(List.of(permission));
    assertThat(service.access(1L, 100L).permissionCodes()).containsExactly("role:read");
    assertThat(service.rolePermissionCodes(1L, 100L, 30L)).containsExactly("role:read");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNPROTECTED_OWNER", "PROTECTED_CUSTOM"})
  void ownerBypassRequiresBothProtectedFlagAndOwnerType(String state) {
    activeMember();
    RbacRole role = roles.selectById(30L);
    role.setBuiltInType(state.equals("UNPROTECTED_OWNER") ? "OWNER" : "CUSTOM");
    role.setProtectedRole(state.equals("PROTECTED_CUSTOM"));
    when(grants.selectActivePermissionCodes(100L, 30L)).thenReturn(List.of("activity:read"));
    assertThat(service.access(1L, 100L).permissionCodes()).containsExactly("activity:read");
  }

  @Test
  void rolePermissionReadRequiresRoleReadBeforeReturningGrants() {
    activeMember();
    assertThatThrownBy(() -> service.rolePermissionCodes(1L, 100L, 30L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code()).isEqualTo("PERMISSION_DENIED");
    when(grants.countActivePermission(100L, 30L, "role:read")).thenReturn(1L);
    when(grants.selectActivePermissionCodes(100L, 30L)).thenReturn(List.of("activity:read"));
    assertThat(service.rolePermissionCodes(1L, 100L, 30L)).containsExactly("activity:read");
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE", "DELETED", "OTHER_ORGANIZATION"})
  void rolePermissionReadHidesUnusableTargetRole(String state) {
    activeMember();
    when(grants.countActivePermission(100L, 30L, "role:read")).thenReturn(1L);
    RbacRole target = new RbacRole();
    target.setId(40L);
    target.setOrganizationId(state.equals("OTHER_ORGANIZATION") ? 200L : 100L);
    target.setStatus(state.equals("OTHER_ORGANIZATION") ? "ACTIVE" : state);
    when(roles.selectById(40L)).thenReturn(state.equals("MISSING") ? null : target);
    assertThatThrownBy(() -> service.rolePermissionCodes(1L, 100L, 40L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code()).isEqualTo("ORGANIZATION_RESOURCE_NOT_FOUND");
  }

  private void activeMember() {
    OrganizationMember member = new OrganizationMember();
    member.setId(8L);
    member.setUserId(1L);
    member.setOrganizationId(100L);
    member.setRoleId(30L);
    member.setStatus("ACTIVE");
    when(members.selectActiveMember(1L, 100L)).thenReturn(member);
    RbacRole role = new RbacRole();
    role.setId(30L);
    role.setOrganizationId(100L);
    role.setName("Reader");
    role.setStatus("ACTIVE");
    role.setBuiltInType("CUSTOM");
    role.setProtectedRole(false);
    when(roles.selectById(30L)).thenReturn(role);
  }
}
