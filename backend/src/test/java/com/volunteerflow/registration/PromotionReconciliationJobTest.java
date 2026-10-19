package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PromotionReconciliationJobTest {
  @Test
  void claimsOnlyIndexedDueTasks() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.properties.setBatchSize(2);
    f.task(20, "PENDING", f.now);
    f.task(21, "RETRY", f.now);
    f.task(22, "PENDING", f.now);
    f.task(23, "PENDING", f.now.plusMinutes(1));
    f.task(24, "DONE", f.now);
    assertThat(f.service.claimBatch("owner")).extracting(PromotionReconcileTask::getPositionId)
        .containsExactly(20L, 22L);
    assertThat(f.discoveryLimits).containsExactly(2);
    assertThat(f.row(21).getStatus()).isEqualTo("RETRY");
    assertThat(f.row(23).getStatus()).isEqualTo("PENDING");
  }

  @Test
  void oneFailedPositionDoesNotRollBackCommittedPredecessorsOrStopBatch() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.task(20, "PENDING", f.now);
    f.task(21, "PENDING", f.now);
    f.task(22, "PENDING", f.now);
    f.failedPositions.add(21L);
    assertThat(new PromotionReconciliationJob(f.service).reconcileBatch()).isEqualTo(2);
    assertThat(f.row(20).getProcessedVersion()).isEqualTo(1);
    assertThat(f.row(21).getStatus()).isEqualTo("RETRY");
    assertThat(f.row(22).getStatus()).isEqualTo("DONE");
    assertThat(f.activeOffers).hasSize(1);
    assertThat(f.transactions.rollbacks).isEqualTo(1);
    assertThat(f.transactions.commits).isEqualTo(4); // claim, two items, retry
  }

  @Test
  void leaseOwnerIsFreshForEveryBatch() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    PromotionReconciliationJob job = new PromotionReconciliationJob(f.service);
    job.reconcileBatch();
    job.reconcileBatch();
    assertThat(f.claimOwners).hasSize(2).doesNotHaveDuplicates();
  }

  @Test
  void failuresLogOnlyFixedCategoriesWithoutMessagesOrStackTraces() {
    PromotionReconciliationService service = mock(PromotionReconciliationService.class);
    PromotionReconcileTask task = new PromotionReconcileTask();
    task.setPositionId(20L); task.setRequestVersion(1L);
    when(service.claimBatch(anyString())).thenThrow(new IllegalStateException("secret claim payload"))
        .thenReturn(java.util.List.of(task));
    when(service.reconcile(eq(20L), eq(1L), anyString()))
        .thenThrow(new IllegalStateException("secret business payload"));
    when(service.retry(eq(task), anyString())).thenThrow(new IllegalStateException("secret retry payload"));
    Logger logger = (Logger) LoggerFactory.getLogger(PromotionReconciliationJob.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
    try {
      PromotionReconciliationJob job = new PromotionReconciliationJob(service);
      assertThat(job.reconcileBatch()).isZero();
      assertThat(job.reconcileBatch()).isZero();
      assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
          "Promotion reconciliation failed category=CLAIM_FAILED",
          "Promotion reconciliation failed; positionId=20 category=RECONCILIATION_FAILED",
          "Promotion reconciliation failed; positionId=20 category=RETRY_STATE_FAILED");
      assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    } finally {
      logger.detachAppender(logs); logs.stop();
    }
  }
}
