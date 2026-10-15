package com.volunteerflow.activity;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers cache eviction until the surrounding database transaction has committed. */
@Component
public class ActivityDetailCacheInvalidator {

  private final ActivityDetailCache cache;

  public ActivityDetailCacheInvalidator(ActivityDetailCache cache) {
    this.cache = cache;
  }

  public void evictAfterCommit(Long activityId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              cache.evict(activityId);
            }
          });
      return;
    }
    cache.evict(activityId);
  }
}
