package com.volunteerflow.checkin;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best-effort Redis cleanup that can never decide the MySQL transaction outcome. */
@Component
public class DynamicCheckinTokenInvalidator {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(DynamicCheckinTokenInvalidator.class);

  private final DynamicCheckinTokenStore tokens;

  public DynamicCheckinTokenInvalidator(DynamicCheckinTokenStore tokens) {
    this.tokens = tokens;
  }

  public void afterCommit(long sessionId, Instant closedAt) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          evictBestEffort(sessionId, closedAt);
        }
      });
      return;
    }
    evictBestEffort(sessionId, closedAt);
  }

  private void evictBestEffort(long sessionId, Instant closedAt) {
    try {
      tokens.evictRecent(sessionId, closedAt);
    } catch (RuntimeException exception) {
      // Never log the exception message because a driver error may include Redis values.
      LOGGER.warn("Dynamic check-in cleanup failed: sessionId={}, failureType={}",
          sessionId, exception.getClass().getName());
    }
  }
}
