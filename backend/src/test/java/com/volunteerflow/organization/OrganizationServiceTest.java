package com.volunteerflow.organization;

import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.SecureTokenGenerator;
import com.volunteerflow.rbac.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrganizationServiceTest {
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final OrganizationInviteMapper inviteMapper = mock(OrganizationInviteMapper.class);
    private final OrganizationMemberMapper memberMapper = mock(OrganizationMemberMapper.class);
    private final RbacRoleMapper roleMapper = mock(RbacRoleMapper.class);
    private final RbacPermissionMapper permissionMapper = mock(RbacPermissionMapper.class);
    private final RbacRolePermissionMapper rolePermissionMapper = mock(RbacRolePermissionMapper.class);
    private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    private final OrganizationService service = new OrganizationService(organizationMapper, inviteMapper, memberMapper,
            roleMapper, permissionMapper, rolePermissionMapper, authorization, auditService,
            new SecureTokenGenerator(), clock);

    @Test
    void inviteStoresHashAndDuplicateJoinDoesNotConsumeUse() {
        OrganizationInvite invite = new OrganizationInvite();
        invite.setId(1L); invite.setOrganizationId(100L); invite.setDefaultRoleId(30L);
        invite.setCodeHash(OrganizationService.hashInvite("join-secret")); invite.setMaxUses(50); invite.setUsedCount(0);
        invite.setStatus("ACTIVE"); invite.setExpiresAt(java.time.LocalDateTime.of(2026, 9, 16, 0, 0));
        when(inviteMapper.selectByCodeHashForUpdate(invite.getCodeHash())).thenReturn(invite);
        OrganizationMember existing = new OrganizationMember(); existing.setId(9L);
        when(memberMapper.selectByOrganizationAndUser(100L, 2L)).thenReturn(existing);

        OrganizationMember result = service.joinByInvite(2L, "join-secret");

        assertThat(result.getId()).isEqualTo(9L);
        verify(inviteMapper, never()).updateById(org.mockito.ArgumentMatchers.<OrganizationInvite>any());
        verify(memberMapper, never()).insert(org.mockito.ArgumentMatchers.<OrganizationMember>any());
    }

    @Test
    void createInviteNeverPersistsRawCode() {
        doNothing().when(authorization).requirePermission(1L, 100L, "member:invite");
        RbacRole memberRole = new RbacRole(); memberRole.setId(30L); memberRole.setOrganizationId(100L);
        when(roleMapper.selectBuiltIn(100L, "MEMBER")).thenReturn(memberRole);

        InviteCode result = service.createInvite(1L, 100L, new CreateInviteRequest(null, null));

        ArgumentCaptor<OrganizationInvite> captor = ArgumentCaptor.forClass(OrganizationInvite.class);
        verify(inviteMapper).insert(captor.capture());
        assertThat(result.code()).isNotBlank();
        assertThat(captor.getValue().getCodeHash()).hasSize(64).isNotEqualTo(result.code());
        assertThat(captor.getValue().getMaxUses()).isEqualTo(50);
    }
}
