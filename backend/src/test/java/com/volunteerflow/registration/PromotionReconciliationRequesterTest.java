package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class PromotionReconciliationRequesterTest {
  @Test
  void unsuccessfulTaskWriteMustFailTheBusinessTransaction() {
    ReconciliationFixture f = new ReconciliationFixture();
    PromotionReconcileTaskMapper mapper = mock(PromotionReconcileTaskMapper.class);
    PromotionReconciliationRequester requester = f.proxy(new PromotionReconciliationRequester(mapper));
    assertThatThrownBy(() -> f.inTransaction(() -> requester.request(10L, 20L, f.now)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(f.transactions.commits).isZero();
    assertThat(f.transactions.rollbacks).isEqualTo(1);
  }

  @Test
  void coalescesRepeatedRequestsByIncrementingVersion() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.request();
    assertThat(f.row(20).getRequestVersion()).isEqualTo(2);
    assertThat(f.row(20).getProcessedVersion()).isZero();
    assertThat(f.row(20).getStatus()).isEqualTo("PENDING");
    assertThat(f.transactions.commits).isEqualTo(2);
  }

  @Test
  void requesterRequiresTheBusinessTransaction() {
    ReconciliationFixture f = new ReconciliationFixture();
    assertThatThrownBy(() -> f.requester.request(10L, 20L, f.now))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    assertThat(f.rows).isEmpty();
  }

  @Test
  void processingRequestPreservesLeaseAndEarliestDueTime() {
    ReconciliationFixture f = new ReconciliationFixture();
    f.request();
    f.service.claimBatch("owner");
    f.requestAt(f.now.minusSeconds(1));
    f.requestAt(f.now.plusHours(1));
    assertThat(f.row(20).getRequestVersion()).isEqualTo(3);
    assertThat(f.row(20).getLockedBy()).isEqualTo("owner");
    assertThat(f.row(20).getStatus()).isEqualTo("PROCESSING");
    assertThat(f.row(20).getNextRunAt()).isEqualTo(f.now.minusSeconds(1));
  }

  @Test
  void invalidRequestsDoNotWriteTasks() {
    ReconciliationFixture f = new ReconciliationFixture();
    assertThatThrownBy(() -> f.inTransaction(() -> f.requester.request(null, 20L, f.now)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.inTransaction(() -> f.requester.request(10L, 0L, f.now)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.inTransaction(() -> f.requester.request(10L, 20L, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(f.rows).isEmpty();
  }
}
