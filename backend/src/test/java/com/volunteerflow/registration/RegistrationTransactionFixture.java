package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.*;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.organization.OrganizationMemberMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Real services/Outbox codec/Spring advice, transactional in-memory mapper boundary; no live DB. */
final class RegistrationTransactionFixture {
  final List<Object> committed = new ArrayList<>();
  final List<Object> staged = new ArrayList<>();
  final Transactions transactions = new Transactions();
  final OutboxEventMapper outbox = mock(OutboxEventMapper.class);
  final AuditLogMapper audits = mock(AuditLogMapper.class);
  final KafkaEventGateway unavailableKafka = mock(KafkaEventGateway.class);
  final DomainEventCodec codec = new DomainEventCodec(JsonMapper.builder().findAndAddModules().build());
  final RegistrationSubmissionService service;
  final RuntimeException insertFailure = new IllegalStateException("Outbox insert failed");
  boolean failOutbox;

  RegistrationTransactionFixture(ActivityRegistrationPolicyService policies,
      OrganizationMemberMapper members, RegistrationMapper registrations,
      RegistrationCycleMapper cycles, RegistrationAnswerMapper answers,
      PromotionOfferMapper offers, Clock clock) {
    when(registrations.insert(any(Registration.class))).thenAnswer(call -> stage(call.getArgument(0)));
    when(cycles.insert(any(RegistrationCycle.class))).thenAnswer(call -> stage(call.getArgument(0)));
    when(answers.insert(any(RegistrationAnswer.class))).thenAnswer(call -> stage(call.getArgument(0)));
    when(audits.insert(any(AuditLog.class))).thenAnswer(call -> stage(call.getArgument(0)));
    when(outbox.insert(any(OutboxEvent.class))).thenAnswer(call -> {
      assertThat(staged).anyMatch(Registration.class::isInstance)
          .anyMatch(RegistrationCycle.class::isInstance).anyMatch(AuditLog.class::isInstance);
      if (failOutbox) throw insertFailure;
      return stage(call.getArgument(0));
    });
    when(unavailableKafka.publish(any(), any(), any())).thenReturn(
        CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));
    DomainEventPublisher publisher = proxy(new TransactionalOutboxEventPublisher(outbox, codec,
        new EventTopicRouter("activity-topic", "registration-topic", "checkin-topic"), clock));
    service = proxy(new RegistrationSubmissionService(policies, members, registrations, cycles,
        answers, offers, new RegistrationAnswerValidator(), new AuditService(audits,
            JsonMapper.builder().build()), publisher, clock));
  }

  private int stage(Object row) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(committed).isEmpty();
    staged.add(row);
    return 1;
  }

  @SuppressWarnings("unchecked")
  private <T> T proxy(T target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  final class Transactions extends AbstractPlatformTransactionManager {
    int commits;
    int rollbacks;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { staged.clear(); }
    @Override protected void doCommit(DefaultTransactionStatus status) {
      commits++; committed.addAll(staged); staged.clear();
    }
    @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; staged.clear(); }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {}
  }
}
