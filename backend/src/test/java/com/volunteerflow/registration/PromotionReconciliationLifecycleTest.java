package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Lifecycle and bounded row-lock-model regressions, not a live MySQL contention claim. */
class PromotionReconciliationLifecycleTest {
  @ParameterizedTest
  @CsvSource({"PENDING,CANCELED", "RETRY,CANCELED", "PENDING,FINISHED", "RETRY,FINISHED",
      "PENDING,DRAFT", "RETRY,DRAFT"})
  void delayedTasksAfterCancellationOrFinalizationCompleteWithoutOfferOrNotification(String status, String parentStatus) {
    ReconciliationFixture f = new ReconciliationFixture();
    f.task(20, status, f.now.minusSeconds(1));
    f.activity.setStatus(parentStatus);
    f.service.claimBatch("owner");
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("UNCHANGED");
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
    assertThat(f.row(20).getProcessedVersion()).isEqualTo(1);
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.events.commands()).isEmpty();
    verifyNoInteractions(f.audit);
  }

  @ParameterizedTest
  @CsvSource({"PENDING,0", "RETRY,0", "PENDING,-1", "RETRY,-1"})
  void publishedParentAtOrAfterConfirmationEndCannotCreateDelayedOffer(String status, int secondsFromNow) {
    ReconciliationFixture f = new ReconciliationFixture();
    f.task(20, status, f.now);
    f.activity.setActivityEndAt(f.now.plusSeconds(secondsFromNow));
    f.service.claimBatch("owner");
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("UNCHANGED");
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.events.commands()).isEmpty();
  }

  @Test
  void existingConfirmationWindowStillAllowsPromotionAfterRegistrationCloses() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.activity.setRegistrationEndAt(f.now.minusHours(1));
    f.activity.setActivityEndAt(f.now.plusNanos(1_000_000));
    f.request(); f.service.claimBatch("owner");
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("OFFERED");
    assertThat(f.activeOffers).hasSize(1);
    assertThat(f.events.commands()).hasSize(1);
  }

  @Test
  void confirmationEndIsResampledAfterWaitingForPositionLock() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.activity.setActivityEndAt(f.now.plusSeconds(5));
    f.afterPositionLock = () -> f.now = f.activity.getActivityEndAt();
    f.request(); f.service.claimBatch("owner");
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("UNCHANGED");
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.events.commands()).isEmpty();
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
  }

  @Test
  void lockedPositionMustStillMatchTheSnapshotAndLockedAncestor() {
    ReconciliationFixture f = new ReconciliationFixture();
    when(f.policies.lockActivityForRegistrationChange(10L, 11L)).thenAnswer(call -> {
      f.position.setActivityId(999L); return f.activity;
    });
    f.request(); f.service.claimBatch("owner");
    assertThat(f.service.reconcile(20L, 1, "owner").name()).isEqualTo("UNCHANGED");
    assertThat(f.activeOffers).isEmpty();
    assertThat(f.events.commands()).isEmpty();
    assertThat(f.row(20).getStatus()).isEqualTo("DONE");
  }

  @Test
  void missingOrMismatchedParentNeverCreatesAnOffer() {
    for (boolean missing : new boolean[]{true, false}) {
      ReconciliationFixture f = new ReconciliationFixture();
      if (missing) when(f.policies.lockActivityForRegistrationChange(10L, 11L)).thenReturn(null);
      else f.activity.setOrganizationId(999L);
      f.request(); f.service.claimBatch("owner");
      f.service.reconcile(20L, 1, "owner");
      assertThat(f.activeOffers).isEmpty();
      assertThat(f.events.commands()).isEmpty();
    }
  }

  @Test
  void cancellationWinningAncestorLockPreventsQueuedReconciliationOffer() throws Exception {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request(); f.service.claimBatch("owner");
    ReentrantLock ancestor = new ReentrantLock();
    CountDownLatch cancellationLocked = new CountDownLatch(1), finishCancellation = new CountDownLatch(1),
        workerAttempted = new CountDownLatch(1);
    when(f.policies.lockActivityForRegistrationChange(10L, 11L)).thenAnswer(call -> {
      workerAttempted.countDown();
      assertThat(f.operations).doesNotContain("position", "task");
      if (!ancestor.tryLock(5, TimeUnit.SECONDS)) throw new AssertionError("Ancestor lock timeout");
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { ancestor.unlock(); }
      });
      f.operations.add("activity"); return f.activity;
    });
    when(f.offers.insert(any(PromotionOffer.class))).thenAnswer(call -> {
      workerAttempted.countDown(); f.activeOffers.add(call.getArgument(0)); return 1;
    });
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> cancellation = workers.submit(() -> {
        ancestor.lock();
        try {
          cancellationLocked.countDown(); await(finishCancellation); f.activity.setStatus("CANCELED");
        } finally { ancestor.unlock(); }
      });
      await(cancellationLocked);
      Future<?> reconciliation = workers.submit(() -> f.service.reconcile(20L, 1, "owner"));
      await(workerAttempted); finishCancellation.countDown();
      cancellation.get(5, TimeUnit.SECONDS); reconciliation.get(5, TimeUnit.SECONDS);
      assertThat(f.activeOffers).isEmpty();
      assertThat(f.events.commands()).isEmpty();
      assertThat(f.row(20).getStatus()).isEqualTo("DONE");
      assertThat(f.operations).containsSubsequence("snapshot", "activity", "position", "task", "complete");
    } finally {
      finishCancellation.countDown(); workers.shutdownNow();
      assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void await(CountDownLatch latch) {
    try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded fixture wait timed out"); }
    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
  }
}
