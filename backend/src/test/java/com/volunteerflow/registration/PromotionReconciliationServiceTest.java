package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class PromotionReconciliationServiceTest {
  @Test
  void blankOrOversizedLeaseOwnerCannotDiscoverOrExecuteWork() {
    ReconciliationFixture f = new ReconciliationFixture();
    for (String owner : new String[]{null, " ", "x".repeat(129)}) {
      assertThatThrownBy(() -> f.service.claimBatch(owner)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> f.service.reconcile(20L, 1, owner)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(f.discoveryLimits).isEmpty();
    assertThat(f.activeOffers).isEmpty();
  }

  @Test
  void createsAtMostOneRequiredActiveOffer() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.position.setCapacity(2);
    f.request();
    f.service.claimBatch("first");
    assertThat(f.service.reconcile(20L, 1, "first").name()).isEqualTo("OFFERED");
    assertThat(f.activeOffers).hasSize(1);
    assertThat(f.activeOffers.get(0).getRegistrationCycleId()).isEqualTo(101);
    assertThat(f.row(20).getStatus()).isEqualTo("PENDING");
    f.service.claimBatch("second");
    f.service.reconcile(20L, 2, "second");
    assertThat(f.activeOffers).hasSize(2);
    f.service.claimBatch("third");
    f.service.reconcile(20L, 3, "third");
    assertThat(f.activeOffers).hasSize(2);
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
  }

  @Test
  void secondRunIsIdempotent() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("first");
    f.service.reconcile(20L, 1, "first");
    f.service.claimBatch("second");
    f.service.reconcile(20L, 2, "second");
    assertThat(f.activeOffers).hasSize(1);
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
    assertThat(f.service.reconcile(20L, 2, "second").name()).isEqualTo("STALE");
    assertThat(f.activeOffers).hasSize(1);
  }

  @Test
  void newRequestDuringProcessingRemainsPending() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.waitlist.clear();
    f.request();
    f.service.claimBatch("owner");
    f.request();
    f.service.reconcile(20L, 1, "owner");
    assertThat(f.row(20).getRequestVersion()).isEqualTo(2);
    assertThat(f.row(20).getProcessedVersion()).isEqualTo(1);
    assertThat(f.row(20).getStatus()).isEqualTo("PENDING");
  }

  @Test
  void staleWorkerCannotCompleteNewLease() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("old");
    f.now = f.now.plusSeconds(61);
    f.service.claimBatch("new");
    assertThat(f.service.reconcile(20L, 1, "old").name()).isEqualTo("STALE");
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.row(20).getLockedBy()).isEqualTo("new");
    assertThat(f.service.reconcile(20L, 1, "new").name()).isEqualTo("OFFERED");
    assertThat(f.activeOffers).hasSize(1);
  }

  @Test
  void expiredLeaseCannotExecuteEvenBeforeReclamation() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("owner");
    f.now = f.now.plusSeconds(60);
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("STALE");
    assertThat(f.activeOffers).isEmpty();
  }

  @Test
  void leaseExpiryDuringOfferCreationRollsBackOfferAndCompletion() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("owner");
    when(f.offers.insert(any(PromotionOffer.class))).thenAnswer(call -> {
      f.activeOffers.add(call.getArgument(0)); f.now = f.now.plusSeconds(60); return 1;
    });
    assertThatThrownBy(() -> f.service.reconcile(20L, 1, "owner"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.row(20).getProcessedVersion()).isZero();
    assertThat(f.row(20).getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void unsuccessfulContinuationWriteRollsBackOfferRatherThanLosingRemainingCapacity() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.position.setCapacity(2);
    f.request(); f.service.claimBatch("owner"); f.failRequest = true;
    assertThatThrownBy(() -> f.service.reconcile(20L, 1, "owner"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.row(20).getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void failedCompletionRollsBackOfferAndLeavesTaskRecoverable() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("owner");
    f.failCompletion = true;
    assertThatThrownBy(() -> f.service.reconcile(20L, 1, "owner"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.row(20).getRequestVersion()).isEqualTo(1);
    assertThat(f.row(20).getProcessedVersion()).isZero();
    assertThat(f.row(20).getStatus()).isEqualTo("PROCESSING");
  }

  @Test
  void retriesAreBoundedAndStaleOwnersCannotRetry() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    for (int attempt = 0; attempt < 12; attempt++) {
      PromotionReconcileTask claimed = f.service.claimBatch("owner-" + attempt).get(0);
      f.service.retry(claimed, claimed.getLockedBy());
      long seconds = java.time.Duration.between(f.now, f.row(20).getNextRunAt()).getSeconds();
      assertThat(seconds).isBetween(5L, 300L);
      if (attempt == 0) assertThat(seconds).isEqualTo(5);
      if (attempt == 11) assertThat(seconds).isEqualTo(300);
      assertThat(f.row(20).getLastError()).isEqualTo("RECONCILIATION_FAILED");
      f.now = f.row(20).getNextRunAt();
    }
    PromotionReconcileTask old = f.service.claimBatch("old").get(0);
    f.now = f.now.plusSeconds(61);
    f.service.claimBatch("new");
    assertThat(f.service.retry(old, "old")).isFalse();
    assertThat(f.row(20).getLockedBy()).isEqualTo("new");
  }

  @Test
  void organizationMismatchCannotPromoteAnotherTenant() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.row(20).setOrganizationId(999L);
    f.service.claimBatch("owner");
    f.service.reconcile(20L, 1, "owner");
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
  }

  @Test
  void reviewAndInactivePositionsNeverAutoPromote() {
    for (String state : new String[]{"REVIEW", "INACTIVE"}) {
      ReconciliationFixture f = new ReconciliationFixture();
      if (state.equals("REVIEW")) f.position.setRegistrationMode("REVIEW");
      else f.position.setStatus("INACTIVE");
      f.request(); f.service.claimBatch("owner");
      assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("UNCHANGED");
      assertThat(f.activeOffers).isEmpty();
      assertThat(f.row(20).getStatus()).isEqualTo("DONE");
    }
  }

  @Test
  void lockOrderNeverAcquiresMembershipAfterPosition() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("owner");
    f.service.reconcile(20L, 1, "owner");
    assertThat(f.operations).containsSubsequence("position", "task", "position", "cycle", "offer", "complete");
    verifyNoInteractions(f.members);
  }
}
