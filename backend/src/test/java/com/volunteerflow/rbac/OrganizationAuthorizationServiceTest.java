package com.volunteerflow.rbac;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import org.junit.jupiter.api.Test;

class OrganizationAuthorizationServiceTest {
  private final OrganizationMemberMapper memberMapper = mock(OrganizationMemberMapper.class);
  private final RbacRoleMapper roleMapper = mock(RbacRoleMapper.class);
  private final RbacRolePermissionMapper rolePermissionMapper =
      mock(RbacRolePermissionMapper.class);
  private final OrganizationAuthorizationService service =
      new OrganizationAuthorizationService(memberMapper, roleMapper, rolePermissionMapper);

  @Test
  void protectedOwnerHasEveryCurrentAndFuturePermission() {
    OrganizationMember member = member(10L);
    RbacRole owner = role(10L, "OWNER", true);
    when(memberMapper.selectActiveMember(1L, 100L)).thenReturn(member);
    when(roleMapper.selectById(10L)).thenReturn(owner);

    assertThatCode(() -> service.requirePermission(1L, 100L, "future:permission"))
        .doesNotThrowAnyException();
  }

  @Test
  void ordinaryMemberWithoutPermissionIsForbidden() {
    OrganizationMember member = member(20L);
    when(memberMapper.selectActiveMember(1L, 100L)).thenReturn(member);
    when(roleMapper.selectById(20L)).thenReturn(role(20L, "MEMBER", false));
    when(rolePermissionMapper.countActivePermission(100L, 20L, "activity:publish")).thenReturn(0L);

    assertThatThrownBy(() -> service.requirePermission(1L, 100L, "activity:publish"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).status().value())
        .isEqualTo(403);
  }

  private OrganizationMember member(Long roleId) {
    OrganizationMember member = new OrganizationMember();
    member.setOrganizationId(100L);
    member.setUserId(1L);
    member.setRoleId(roleId);
    member.setStatus("ACTIVE");
    return member;
  }

  private RbacRole role(Long id, String type, boolean protectedRole) {
    RbacRole role = new RbacRole();
    role.setId(id);
    role.setOrganizationId(100L);
    role.setBuiltInType(type);
    role.setProtectedRole(protectedRole);
    role.setStatus("ACTIVE");
    return role;
  }
}
