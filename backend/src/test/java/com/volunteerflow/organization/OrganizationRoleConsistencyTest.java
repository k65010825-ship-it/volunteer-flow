package com.volunteerflow.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.auth.SecureTokenGenerator;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Real service and Spring advice with a simulated row mutex; not a live MySQL concurrency test. */
class OrganizationRoleConsistencyTest {
  private final OrganizationMapper organizations = mock(OrganizationMapper.class);
  private final OrganizationInviteMapper invites = mock(OrganizationInviteMapper.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final RbacRoleMapper roles = mock(RbacRoleMapper.class);
  private final RbacRolePermissionMapper grants = mock(RbacRolePermissionMapper.class);
  private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  private final AuditService audit = mock(AuditService.class);
  private final OrganizationService target = new OrganizationService(organizations, invites, members,
      mock(AppUserMapper.class), roles, mock(RbacPermissionMapper.class), grants, authorization,
      audit, new SecureTokenGenerator(), Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));

  @BeforeEach
  void activeOrganizationAndCustomRole() {
    Organization organization = new Organization();
    organization.setId(100L);
    organization.setStatus("ACTIVE");
    when(organizations.selectByIdForUpdate(100L)).thenReturn(organization);
    when(roles.selectById(30L)).thenReturn(role("ACTIVE", 100L));
    OrganizationMember member = new OrganizationMember();
    member.setId(8L);
    member.setOrganizationId(100L);
    member.setRoleId(9L);
    member.setStatus("ACTIVE");
    when(members.selectById(8L)).thenReturn(member);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ASSIGN", "DEACTIVATE", "DELETE", "UPDATE", "PERMISSIONS"})
  void organizationMutexPrecedesAuthorizationAndRoleReads(String operation) {
    switch (operation) {
      case "ASSIGN" -> target.assignRole(1L, 100L, 8L, new OrganizationService.AssignRoleRequest(30L));
      case "DEACTIVATE" -> target.deactivateRole(1L, 100L, 30L);
      case "DELETE" -> target.deleteRole(1L, 100L, 30L);
      case "UPDATE" -> target.updateRole(1L, 100L, 30L,
          new OrganizationService.UpdateRoleRequest("new name", null));
      case "PERMISSIONS" -> target.setRolePermissions(1L, 100L, 30L,
          new OrganizationService.SetRolePermissionsRequest(Set.of()));
      default -> throw new IllegalArgumentException(operation);
    }
    var order = inOrder(organizations, authorization, roles, members);
    order.verify(organizations).selectByIdForUpdate(100L);
    order.verify(authorization).requirePermission(1L, 100L,
        operation.equals("ASSIGN") ? "member:role_assign" : "role:update");
    if (operation.equals("ASSIGN")) order.verify(members).selectById(8L);
    order.verify(roles).selectById(30L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING", "INACTIVE", "DELETED", "OTHER_ORGANIZATION"})
  void unusableInviteDefaultRoleCannotCreateMembershipOrConsumeInvite(String state) {
    OrganizationInvite invite = invite();
    when(invites.selectByCodeHashForUpdate(invite.getCodeHash())).thenReturn(invite);
    when(roles.selectById(30L)).thenReturn(state.equals("MISSING") ? null
        : role(state.equals("OTHER_ORGANIZATION") ? "ACTIVE" : state,
            state.equals("OTHER_ORGANIZATION") ? 200L : 100L));

    assertThatThrownBy(() -> target.joinByInvite(2L, "join-secret"))
        .isInstanceOf(BusinessException.class)
        .extracting(failure -> ((BusinessException) failure).code()).isEqualTo("INVALID_INVITE");
    assertThat(invite.getUsedCount()).isZero();
    verify(members, never()).insert(any(OrganizationMember.class));
    verify(invites, never()).updateById(any(OrganizationInvite.class));
    verifyNoInteractions(audit);
  }

  @Test
  void activeCustomInviteDefaultRoleRemainsSupported() {
    OrganizationInvite invite = invite();
    when(invites.selectByCodeHashForUpdate(invite.getCodeHash())).thenReturn(invite);
    OrganizationMember result = target.joinByInvite(2L, "join-secret");
    assertThat(result.getRoleId()).isEqualTo(30L);
    assertThat(invite.getUsedCount()).isEqualTo(1);
    var order = inOrder(invites, organizations, roles, members);
    order.verify(invites).selectByCodeHashForUpdate(invite.getCodeHash());
    order.verify(organizations).selectByIdForUpdate(100L);
    order.verify(roles).selectById(30L);
    order.verify(members).selectByOrganizationAndUser(100L, 2L);
  }

  @Test
  void cannotDeactivateRoleWithActiveMembers() {
    when(members.countActiveByRole(100L, 30L)).thenReturn(1L);
    assertThatThrownBy(() -> target.deactivateRole(1L, 100L, 30L))
        .isInstanceOf(BusinessException.class)
        .extracting(failure -> ((BusinessException) failure).code()).isEqualTo("ROLE_IN_USE");
    verify(roles, never()).updateById(any(RbacRole.class));
  }

  @Test
  void cannotDeleteRoleReferencedEvenByInactiveMembers() {
    when(members.countByRole(100L, 30L)).thenReturn(1L);
    assertThatThrownBy(() -> target.deleteRole(1L, 100L, 30L))
        .isInstanceOf(BusinessException.class)
        .extracting(failure -> ((BusinessException) failure).code()).isEqualTo("ROLE_IN_USE");
    verify(roles, never()).updateById(any(RbacRole.class));
  }

  @Test
  void concurrentAssignmentWaitsForDeactivationAndRejectsTheCommittedInactiveRole() throws Exception {
    MutexTransactions transactions = new MutexTransactions();
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    OrganizationService service = (OrganizationService) proxy.getProxy();
    CountDownLatch countedMembers = new CountDownLatch(1);
    CountDownLatch finishDeactivation = new CountDownLatch(1);
    CountDownLatch assignmentTriedLock = new CountDownLatch(1);
    CountDownLatch assignmentFinished = new CountDownLatch(1);
    AtomicReference<String> committedStatus = new AtomicReference<>("ACTIVE");
    when(organizations.selectByIdForUpdate(100L)).thenAnswer(call -> {
      if (Thread.currentThread().getName().equals("assign-role")) assignmentTriedLock.countDown();
      transactions.lockOrganization();
      return new Organization();
    });
    when(roles.selectById(30L)).thenAnswer(call -> role(committedStatus.get(), 100L));
    when(members.countActiveByRole(100L, 30L)).thenAnswer(call -> {
      countedMembers.countDown();
      if (!finishDeactivation.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
      return 0L;
    });
    when(roles.updateById(any(RbacRole.class))).thenAnswer(call -> {
      committedStatus.set(((RbacRole) call.getArgument(0)).getStatus());
      return 1;
    });
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> deactivate = executor.submit(() -> service.deactivateRole(1L, 100L, 30L));
      assertThat(countedMembers.await(3, TimeUnit.SECONDS)).isTrue();
      Future<String> assign = executor.submit(() -> {
        Thread.currentThread().setName("assign-role");
        try {
          service.assignRole(1L, 100L, 8L, new OrganizationService.AssignRoleRequest(30L));
          return "ASSIGNED";
        } catch (BusinessException exception) {
          return exception.code();
        } finally {
          assignmentFinished.countDown();
        }
      });
      assertThat(assignmentTriedLock.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(assignmentFinished.await(200, TimeUnit.MILLISECONDS))
          .as("assignment must not complete while deactivation owns the organization mutex").isFalse();
      finishDeactivation.countDown();
      deactivate.get(3, TimeUnit.SECONDS);
      assertThat(assign.get(3, TimeUnit.SECONDS)).isEqualTo("ORGANIZATION_RESOURCE_NOT_FOUND");
      assertThat(committedStatus.get()).isEqualTo("INACTIVE");
      verify(members, never()).updateById(any(OrganizationMember.class));
    } finally {
      finishDeactivation.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }
  }

  private RbacRole role(String status, Long organizationId) {
    RbacRole role = new RbacRole();
    role.setId(30L);
    role.setOrganizationId(organizationId);
    role.setBuiltInType("CUSTOM");
    role.setProtectedRole(false);
    role.setStatus(status);
    return role;
  }

  private OrganizationInvite invite() {
    OrganizationInvite invite = new OrganizationInvite();
    invite.setId(1L);
    invite.setOrganizationId(100L);
    invite.setDefaultRoleId(30L);
    invite.setCodeHash(OrganizationService.hashInvite("join-secret"));
    invite.setMaxUses(50);
    invite.setUsedCount(0);
    invite.setStatus("ACTIVE");
    invite.setExpiresAt(LocalDateTime.of(2026, 9, 16, 0, 0));
    return invite;
  }

  private static class MutexTransactions extends AbstractPlatformTransactionManager {
    private final ReentrantLock organization = new ReentrantLock();

    void lockOrganization() throws InterruptedException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      organization.lockInterruptibly();
    }

    @Override
    protected Object doGetTransaction() { return new Object(); }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
      if (organization.isHeldByCurrentThread()) organization.unlock();
    }
  }
}
