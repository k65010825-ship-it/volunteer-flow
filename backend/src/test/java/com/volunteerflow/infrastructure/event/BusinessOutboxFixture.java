package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.audit.AuditLogMapper;
import com.volunteerflow.audit.AuditService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Real business/audit/publisher advice with staged mapper storage, not a live MySQL proof. */
public final class BusinessOutboxFixture {
  public final List<Object> staged = new ArrayList<>();
  public final List<Object> committed = new ArrayList<>();
  public final DomainEventCodec codec = new DomainEventCodec(JsonMapper.builder().findAndAddModules().build());
  public final AuditLogMapper audits = mock(AuditLogMapper.class);
  public final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
  public final KafkaEventGateway unavailableKafka = mock(KafkaEventGateway.class);
  public final AuditService audit = new AuditService(audits, JsonMapper.builder().findAndAddModules().build());
  public final DomainEventPublisher publisher;
  public final RuntimeException failure = new IllegalStateException("Outbox insert failed");
  public int failAtInsert;
  public int insertAttempts;
  public int commits;
  public int rollbacks;
  public Runnable afterRollback = () -> {};
  private final Transactions transactions = new Transactions();

  public BusinessOutboxFixture(Clock clock) {
    when(audits.insert(any(AuditLog.class))).thenAnswer(call -> stage(call.getArgument(0)));
    when(outbox.insert(any(OutboxEvent.class))).thenAnswer(call -> {
      assertThat(staged).anyMatch(AuditLog.class::isInstance);
      assertThat(staged).anyMatch(row -> !(row instanceof AuditLog) && !(row instanceof OutboxEvent));
      if (++insertAttempts == failAtInsert) throw failure;
      return stage(call.getArgument(0));
    });
    when(unavailableKafka.publish(any(), any(), any())).thenReturn(
        CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
    publisher = proxy(new TransactionalOutboxEventPublisher(outbox, codec,
        new EventTopicRouter("volunteerflow.activity-events.v1", "volunteerflow.registration-events.v1",
            "volunteerflow.checkin-events.v1"), clock));
  }

  public int stage(Object row) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(committed).isEmpty();
    staged.add(row);
    return 1;
  }

  public List<OutboxEvent> rows() {
    return committed.stream().filter(OutboxEvent.class::isInstance).map(OutboxEvent.class::cast).toList();
  }

  @SuppressWarnings("unchecked")
  public <T> T proxy(T target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (T) factory.getProxy();
  }

  private final class Transactions extends AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { staged.clear(); }
    @Override protected void doCommit(DefaultTransactionStatus status) {
      commits++; committed.addAll(staged); staged.clear();
    }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++; staged.clear(); afterRollback.run();
    }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {}
  }
}
