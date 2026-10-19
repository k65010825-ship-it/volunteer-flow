package com.volunteerflow.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.volunteerflow.infrastructure.event.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationEventHandlerTest {
  @Test
  void commitsConsumedMarkerAndNotificationTogether() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    assertThat(f.handler.handle(f.event())).isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
    assertThat(f.transactions.commits).isEqualTo(1);
    assertThat(f.markers).hasSize(1);
    assertThat(f.rows).hasSize(1);
    assertThat(f.markers.get(0).getId()).isPositive();
    assertThat(f.markers.get(0).getConsumerName()).isEqualTo("volunteerflow-notification-v1");
    assertThat(f.markers.get(0).getEventId()).isEqualTo("b1445338-156d-41e3-a131-8f349282125d");
    assertThat(f.markers.get(0).getProcessedAt()).isEqualTo("2026-10-18T14:30:00");
    Notification row = f.rows.get(0);
    assertThat(row.getOrganizationId()).isEqualTo(1001L);
    assertThat(row.getUserId()).isEqualTo(9L);
    assertThat(row.getNotificationType()).isEqualTo("REGISTRATION_SUBMITTED");
    assertThat(row.getTitle()).isEqualTo("Original title");
    assertThat(row.getContent()).isEqualTo("Immutable historical message");
    assertThat(row.getBusinessType()).isEqualTo("REGISTRATION");
    assertThat(row.getBusinessId()).isEqualTo(2001L);
    assertThat(row.getDeduplicationKey()).isEqualTo("b1445338-156d-41e3-a131-8f349282125d:9:IN_APP");
  }

  @Test
  void databaseFailureRollsBackConsumedMarker() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    f.failNotification = true;
    assertThatThrownBy(() -> f.handler.handle(f.event())).isInstanceOf(IllegalStateException.class);
    assertThat(f.transactions.rollbacks).isEqualTo(1);
    assertThat(f.transactions.commits).isZero();
    assertThat(f.markers).isEmpty();
    assertThat(f.rows).isEmpty();
    f.failNotification = false;
    assertThat(f.handler.handle(f.event())).isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
    assertThat(f.markers).hasSize(1);
    assertThat(f.rows).hasSize(1);
  }

  @Test
  void markerFailureCannotCreateNotification() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    doThrow(new IllegalStateException("marker unavailable")).when(f.consumed).insertIfAbsent(any());
    assertThatThrownBy(() -> f.handler.handle(f.event())).isInstanceOf(IllegalStateException.class);
    assertThat(f.transactions.rollbacks).isEqualTo(1);
    assertThat(f.markers).isEmpty();
    assertThat(f.rows).isEmpty();
  }

  @Test
  void supportedFactsRecordConsumptionWithoutNotification() {
    for (String type : List.of("checkin.completed.v1", "checkin.corrected.v1")) {
      NotificationConsumerFixture f = new NotificationConsumerFixture();
      DomainEventEnvelope event = f.event(type, new CheckinStatusPayload(9L, 11L, 12L, "MANUAL"));
      assertThat(f.handler.handle(event)).isEqualTo(NotificationEventHandler.ProcessingResult.NO_NOTIFICATION);
      assertThat(f.handler.handle(event)).isEqualTo(NotificationEventHandler.ProcessingResult.DUPLICATE);
      assertThat(f.markers).hasSize(1);
      assertThat(f.rows).isEmpty();
    }
  }

  @Test
  void mapsEveryApprovedNotificationIntentWithoutBusinessQueries() {
    for (String type : DomainEventTypes.ALL) {
      if (type.equals("checkin.completed.v1") || type.equals("checkin.corrected.v1")) continue;
      NotificationConsumerFixture f = new NotificationConsumerFixture();
      assertThat(f.handler.handle(f.event(type, f.event().payload())))
          .isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
      assertThat(f.rows).hasSize(1);
    }
  }

  @Test
  void rejectsNullBeforeWritingAnyMarker() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    assertThatThrownBy(() -> f.handler.handle(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(f.markers).isEmpty();
    assertThat(f.rows).isEmpty();
  }
}

/** Transactional in-memory database boundary; no MySQL, H2, or broker is contacted. */
class NotificationConsumerFixture {
  final List<ConsumedEvent> markers = new ArrayList<>();
  final List<Notification> rows = new ArrayList<>();
  final List<ConsumedEvent> stagedMarkers = new ArrayList<>();
  final List<Notification> stagedRows = new ArrayList<>();
  final ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
  final NotificationMapper notifications = mock(NotificationMapper.class);
  final TestTransactions transactions = new TestTransactions();
  final DomainEventCodec codec = new DomainEventCodec(JsonMapper.builder().findAndAddModules().build());
  final NotificationEventHandler handler;
  boolean failNotification;

  NotificationConsumerFixture() {
    when(consumed.insertIfAbsent(any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      ConsumedEvent marker = call.getArgument(0);
      if (markers.stream().anyMatch(existing -> existing.getEventId().equals(marker.getEventId())
          && existing.getConsumerName().equals(marker.getConsumerName()))) return 0;
      stagedMarkers.add(marker);
      return 1;
    });
    when(notifications.insertIdempotent(any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(stagedMarkers).hasSize(1);
      assertThat(markers).isEmpty(); // Marker has not committed before the sink.
      if (failNotification) throw new IllegalStateException("password=DB_SECRET private-event-content RAW_DB_ERROR");
      stagedRows.add(call.getArgument(0));
      return 1;
    });
    NotificationService sink = proxy(new NotificationService(notifications), NotificationService.class);
    handler = proxy(new NotificationEventHandler(consumed, sink,
        Clock.fixed(Instant.parse("2026-10-18T14:30:00Z"), ZoneId.of("Asia/Shanghai"))),
        NotificationEventHandler.class);
  }

  <T> T proxy(T target, Class<T> type) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return type.cast(factory.getProxy());
  }

  DomainEventEnvelope event() {
    return event("registration.submitted.v1", new NotificationIntentPayload(9L, "REGISTRATION_SUBMITTED",
        "Original title", "Immutable historical message", "REGISTRATION", 2001L));
  }

  DomainEventEnvelope event(String type, DomainEventPayload payload) {
    return new DomainEventEnvelope(UUID.fromString("b1445338-156d-41e3-a131-8f349282125d"), type,
        Instant.parse("2026-10-18T14:30:00Z"), 1001L, "REGISTRATION", "2001", 3001L, "request-123", payload);
  }

  class TestTransactions extends AbstractPlatformTransactionManager {
    int commits;
    int rollbacks;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
      stagedMarkers.clear(); stagedRows.clear();
    }
    @Override protected void doCommit(DefaultTransactionStatus status) {
      commits++; markers.addAll(stagedMarkers); rows.addAll(stagedRows);
      stagedMarkers.clear(); stagedRows.clear();
    }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++; stagedMarkers.clear(); stagedRows.clear();
    }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {}
  }
}
