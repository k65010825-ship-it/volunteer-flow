package com.volunteerflow.activity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ActivityDetailCacheInvalidatorTest {

  private final ActivityDetailCache cache = mock(ActivityDetailCache.class);
  private final ActivityDetailCacheInvalidator invalidator =
      new ActivityDetailCacheInvalidator(cache);

  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void activeTransactionEvictsOnlyAfterCommit() {
    beginTransactionSynchronization();

    invalidator.evictAfterCommit(10L);

    verifyNoInteractions(cache);
    TransactionSynchronization synchronization =
        TransactionSynchronizationManager.getSynchronizations().get(0);
    synchronization.afterCommit();
    verify(cache).evict(10L);
  }

  @Test
  void rolledBackTransactionNeverEvicts() {
    beginTransactionSynchronization();

    invalidator.evictAfterCommit(10L);
    TransactionSynchronizationManager.getSynchronizations().get(0)
        .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    verifyNoInteractions(cache);
  }

  @Test
  void callOutsideTransactionEvictsImmediately() {
    invalidator.evictAfterCommit(10L);

    verify(cache).evict(10L);
  }

  private void beginTransactionSynchronization() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
  }
}
