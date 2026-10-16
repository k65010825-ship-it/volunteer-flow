package com.volunteerflow.checkin;

import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DynamicCheckinTokenInvalidatorTest {
  private final DynamicCheckinTokenStore tokens = mock(DynamicCheckinTokenStore.class);
  private final DynamicCheckinTokenInvalidator invalidator =
      new DynamicCheckinTokenInvalidator(tokens);
  private final Instant closedAt = Instant.parse("2026-10-16T04:00:00Z");

  @Test
  void closeEvictsDynamicKeysOnlyAfterCommit() {
    TestTransactions transactions = new TestTransactions();

    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      invalidator.afterCommit(101L, closedAt);
      verifyNoInteractions(tokens);
    });

    verify(tokens).evictRecent(101L, closedAt);
  }

  @Test
  void rollbackDoesNotEvict() {
    TestTransactions transactions = new TestTransactions();

    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      invalidator.afterCommit(101L, closedAt);
      status.setRollbackOnly();
    });

    verifyNoInteractions(tokens);
  }

  @Test
  void withoutTransactionCleanupRunsImmediately() {
    invalidator.afterCommit(101L, closedAt);

    verify(tokens).evictRecent(101L, closedAt);
  }

  @Test
  void redisCleanupFailureDoesNotRollBackClosedSession() {
    TestTransactions transactions = new TestTransactions();
    doThrow(new DynamicCheckinTokenStore.StoreUnavailableException("down"))
        .when(tokens).evictRecent(101L, closedAt);

    new TransactionTemplate(transactions).executeWithoutResult(
        status -> invalidator.afterCommit(101L, closedAt));

    verify(tokens).evictRecent(101L, closedAt);
    org.assertj.core.api.Assertions.assertThat(transactions.commits.get()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(transactions.rollbacks.get()).isZero();
  }

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks.incrementAndGet();
    }
  }
}
