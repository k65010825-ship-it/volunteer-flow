package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PromotionExpiryJobTest {
  private final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
  private final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final ActivityRegistrationPolicyService policies =
      mock(ActivityRegistrationPolicyService.class);
  private final PromotionService promotions = mock(PromotionService.class);
  private final AuditService audit = mock(AuditService.class);
  private final LocalDateTime now = LocalDateTime.of(2026, 9, 17, 12, 0);
  private final PromotionExpiryService service =
      new PromotionExpiryService(offers, cycles, members, policies, promotions, audit);
  private PromotionOffer offer;
  private RegistrationCycle cycle;
  private ActivityPosition position;
  private OrganizationMember member;

  @BeforeEach
  void setUp() {
    offer = new PromotionOffer();
    offer.setId(300L);
    offer.setOrganizationId(10L);
    offer.setActivityId(20L);
    offer.setPositionId(30L);
    offer.setRegistrationCycleId(200L);
    offer.setStatus("PENDING");
    offer.setExpiresAt(now);
    cycle = new RegistrationCycle();
    cycle.setId(200L);
    cycle.setRegistrationId(100L);
    cycle.setOrganizationId(10L);
    cycle.setActivityId(20L);
    cycle.setPositionId(30L);
    cycle.setUserId(7L);
    cycle.setStatus("WAITLISTED");
    position = new ActivityPosition();
    position.setId(30L);
    position.setOrganizationId(10L);
    position.setActivityId(20L);
    position.setRegistrationMode("FIRST_COME");
    position.setStatus("ACTIVE");
    member = new OrganizationMember();
    member.setUserId(7L);
    member.setOrganizationId(10L);
    member.setStatus("ACTIVE");
    when(offers.selectById(300L)).thenReturn(offer);
    when(cycles.selectById(200L)).thenReturn(cycle);
    when(members.selectMemberForUpdateAnyStatus(7L, 10L)).thenReturn(member);
    when(policies.lockPositionForRegistrationChange(30L)).thenReturn(position);
    when(cycles.selectByIdForUpdate(200L)).thenReturn(cycle);
    when(offers.selectByIdForUpdate(300L)).thenReturn(offer);
    when(offers.currentDatabaseTime()).thenReturn(now);
    when(offers.updateById(any(PromotionOffer.class))).thenReturn(1);
    when(cycles.updateById(any(RegistrationCycle.class))).thenReturn(1);
  }

  @Test
  void expiresAtExactDeadlineOnceAndAdvancesFirstComeQueueAfterAudit() {
    assertThat(service.expireOne(300L)).isTrue();
    assertThat(service.expireOne(300L)).isFalse();
    assertThat(offer.getStatus()).isEqualTo("EXPIRED");
    assertThat(offer.getRespondedAt()).isEqualTo(now);
    assertThat(cycle.getStatus()).isEqualTo("PROMOTION_EXPIRED");
    var order = inOrder(members, policies, cycles, offers, audit, promotions);
    order.verify(members).selectMemberForUpdateAnyStatus(7L, 10L);
    order.verify(policies).lockPositionForRegistrationChange(30L);
    order.verify(cycles).selectByIdForUpdate(200L);
    order.verify(offers).selectByIdForUpdate(300L);
    order.verify(offers).currentDatabaseTime();
    order.verify(offers).updateById(offer);
    order.verify(cycles).updateById(cycle);
    order.verify(audit).record(10L, null, "promotion.expired", "promotion_offer", 300L,
        "PROMOTION_EXPIRED");
    order.verify(promotions).offerNextFirstCome(30L, null, "Previous invitation expired");
    verify(offers, times(1)).updateById(any(PromotionOffer.class));
  }

  @Test
  void inactiveMembershipDoesNotPreventExpiryCleanup() {
    member.setStatus("INACTIVE");
    assertThat(service.expireOne(300L)).isTrue();
    assertThat(cycle.getStatus()).isEqualTo("PROMOTION_EXPIRED");
  }

  @Test
  void reviewModeExpiresWithoutAutomaticSelection() {
    position.setRegistrationMode("REVIEW");
    assertThat(service.expireOne(300L)).isTrue();
    verifyNoInteractions(promotions);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACCEPTED", "DECLINED", "CANCELED", "EXPIRED"})
  void rechecksOfferStatusUnderLock(String status) {
    PromotionOffer locked = new PromotionOffer();
    locked.setRegistrationCycleId(200L);
    locked.setOrganizationId(10L);
    locked.setActivityId(20L);
    locked.setPositionId(30L);
    locked.setStatus(status);
    when(offers.selectByIdForUpdate(300L)).thenReturn(locked);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void futureExpiryIsRecheckedUnderLock() {
    offer.setExpiresAt(now.plusNanos(1));
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void terminalCycleIsNeverOverwritten() {
    cycle.setStatus("CANCELED");
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void missingSnapshotIsAnIdempotentNoOp() {
    when(offers.selectById(300L)).thenReturn(null);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
    verifyNoInteractions(members, policies);
  }

  @Test
  void missingMembershipIsAnIdempotentNoOp() {
    when(members.selectMemberForUpdateAnyStatus(7L, 10L)).thenReturn(null);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
    verifyNoInteractions(policies);
  }

  @Test
  void changedCycleOwnerAfterLockIsNotMutated() {
    RegistrationCycle locked = new RegistrationCycle();
    locked.setId(200L);
    locked.setRegistrationId(100L);
    locked.setOrganizationId(10L);
    locked.setActivityId(20L);
    locked.setPositionId(30L);
    locked.setUserId(8L);
    locked.setStatus("WAITLISTED");
    when(cycles.selectByIdForUpdate(200L)).thenReturn(locked);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void changedActivityRelationshipAfterLockIsNotMutated() {
    RegistrationCycle movedCycle = new RegistrationCycle();
    movedCycle.setId(200L);
    movedCycle.setRegistrationId(100L);
    movedCycle.setOrganizationId(10L);
    movedCycle.setActivityId(21L);
    movedCycle.setPositionId(30L);
    movedCycle.setUserId(7L);
    movedCycle.setStatus("WAITLISTED");
    PromotionOffer movedOffer = new PromotionOffer();
    movedOffer.setId(300L);
    movedOffer.setOrganizationId(10L);
    movedOffer.setActivityId(21L);
    movedOffer.setPositionId(30L);
    movedOffer.setRegistrationCycleId(200L);
    movedOffer.setStatus("PENDING");
    movedOffer.setExpiresAt(now);
    position.setActivityId(21L);
    when(cycles.selectByIdForUpdate(200L)).thenReturn(movedCycle);
    when(offers.selectByIdForUpdate(300L)).thenReturn(movedOffer);

    assertThat(service.expireOne(300L)).isFalse();

    verifyNoWrites();
  }

  @Test
  void mismatchedOrganizationIsNotMutated() {
    position.setOrganizationId(11L);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void mismatchedMembershipIsNotMutated() {
    member.setUserId(8L);
    assertThat(service.expireOne(300L)).isFalse();
    verifyNoWrites();
  }

  @Test
  void zeroRowWriteAbortsBeforeAuditAndPromotion() {
    when(offers.updateById(offer)).thenReturn(0);
    assertThatThrownBy(() -> service.expireOne(300L))
        .isInstanceOf(RuntimeException.class);
    verify(cycles, never()).updateById(any(RegistrationCycle.class));
    verifyNoInteractions(audit, promotions);
  }

  @Test
  void auditFailurePropagatesWithoutInvitingNextCandidate() {
    doThrow(new IllegalStateException("audit unavailable")).when(audit)
        .record(10L, null, "promotion.expired", "promotion_offer", 300L, "PROMOTION_EXPIRED");
    assertThatThrownBy(() -> service.expireOne(300L))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(promotions);
  }

  @Test
  void batchUsesConfiguredLimitAndCountsOnlySuccessfulTransitions() {
    PromotionExpiryService worker = mock(PromotionExpiryService.class);
    PromotionExpiryProperties properties = new PromotionExpiryProperties();
    properties.setBatchSize(2);
    when(offers.selectExpiredIds(now, 2)).thenReturn(List.of(300L, 301L));
    when(worker.expireOne(300L)).thenReturn(true);
    when(worker.expireOne(301L)).thenReturn(false);
    PromotionExpiryJob job = new PromotionExpiryJob(offers, worker, properties);
    assertThat(job.expireBatch()).isEqualTo(1);
    verify(worker).expireOne(300L);
    verify(worker).expireOne(301L);
  }

  @Test
  void itemFailureDoesNotPreventRemainingItems() {
    PromotionExpiryService worker = mock(PromotionExpiryService.class);
    when(offers.selectExpiredIds(now, 100)).thenReturn(List.of(300L, 301L, 302L));
    when(worker.expireOne(300L)).thenReturn(true);
    when(worker.expireOne(301L)).thenThrow(new IllegalStateException("transient failure"));
    when(worker.expireOne(302L)).thenReturn(true);
    PromotionExpiryJob job =
        new PromotionExpiryJob(offers, worker, new PromotionExpiryProperties());
    assertThat(job.expireBatch()).isEqualTo(2);
    verify(worker).expireOne(302L);
  }

  @Test
  void springProxyGivesEachItemItsOwnTransactionAndIsolatesFailure() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    var first = new SimpleTransactionStatus();
    var second = new SimpleTransactionStatus();
    var third = new SimpleTransactionStatus();
    when(transactions.getTransaction(any())).thenReturn(first, second, third);
    when(offers.selectExpiredIds(now, 100)).thenReturn(List.of(300L, 301L, 302L));
    when(offers.selectById(301L)).thenThrow(new IllegalStateException("item failed"));
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(TransactionConfiguration.class);
      context.registerBean(PlatformTransactionManager.class, () -> transactions);
      context.registerBean(PromotionExpiryService.class, () -> service);
      context.registerBean(PromotionExpiryJob.class,
          () -> new PromotionExpiryJob(offers, context.getBean(PromotionExpiryService.class),
              new PromotionExpiryProperties()));
      context.refresh();

      assertThat(context.getBean(PromotionExpiryJob.class).expireBatch()).isEqualTo(1);

      var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
      verify(transactions, times(3)).getTransaction(definitions.capture());
      assertThat(definitions.getAllValues()).allSatisfy(definition -> {
        assertThat(definition.getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getIsolationLevel())
            .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      });
      var order = inOrder(transactions);
      order.verify(transactions).commit(first);
      order.verify(transactions).rollback(second);
      order.verify(transactions).commit(third);
      verify(transactions, never()).rollback(first);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class TransactionConfiguration {}

  private void verifyNoWrites() {
    verify(offers, never()).updateById(any(PromotionOffer.class));
    verify(cycles, never()).updateById(any(RegistrationCycle.class));
    verifyNoInteractions(audit, promotions);
  }
}
