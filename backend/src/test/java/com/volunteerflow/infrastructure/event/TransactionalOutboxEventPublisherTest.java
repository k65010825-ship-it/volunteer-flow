package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.volunteerflow.infrastructure.web.JacksonConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionalOutboxEventPublisherTest {
  private static final Instant NOW = Instant.parse("2026-10-18T14:30:00Z");
  private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
  private final List<OutboxEvent> rows = new ArrayList<>();
  private final ObjectMapper json = configuredMapper();
  private final DomainEventCodec codec = new DomainEventCodec(json);
  private final TestTransactions transactions = new TestTransactions();
  private final TransactionTemplate transaction = new TransactionTemplate(transactions);
  private DomainEventPublisher publisher;

  @BeforeEach
  void setUp() {
    TransactionalOutboxEventPublisher target = new TransactionalOutboxEventPublisher(mapper,
        codec, new EventTopicRouter("activity-topic", "registration-topic", "checkin-topic"),
        Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    publisher = (DomainEventPublisher) proxy.getProxy();
    when(mapper.insert(any(OutboxEvent.class))).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      rows.add(call.getArgument(0));
      return 1;
    });
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void serializesOnlyTheStableEnvelope() throws Exception {
    MDC.put("requestId", "request-123");
    UUID eventId = transaction.execute(status -> publisher.publish(command("registration.submitted.v1", intent(9L))));

    assertThat(rows).hasSize(1);
    OutboxEvent row = rows.get(0);
    assertThat(row.getId()).isPositive();
    assertThat(row.getOrganizationId()).isEqualTo(1001L);
    assertThat(row.getEventId()).isEqualTo(eventId.toString());
    assertThat(row.getEventType()).isEqualTo("registration.submitted.v1");
    assertThat(row.getAggregateType()).isEqualTo("REGISTRATION");
    assertThat(row.getAggregateId()).isEqualTo("2001");
    assertThat(row.getTopic()).isEqualTo("registration-topic");
    assertThat(row.getMessageKey()).isEqualTo("1001:3001");
    assertThat(row.getStatus()).isEqualTo("PENDING");
    assertThat(row.getRetryCount()).isZero();
    assertThat(row.getNextRetryAt()).isEqualTo(LocalDateTime.parse("2026-10-18T14:30:00"));
    assertThat(row.getCreateTime()).isEqualTo(row.getNextRetryAt());
    assertThat(row.getUpdateTime()).isEqualTo(row.getNextRetryAt());
    assertThat(row.getPublishedAt()).isNull();
    JsonNode event = json.readTree(row.getPayloadJson());
    assertThat(fields(event)).containsExactlyInAnyOrder("eventId", "eventType", "occurredAt",
        "organizationId", "aggregateType", "aggregateId", "activityId", "requestId", "payload");
    assertThat(event.get("occurredAt").asText()).isEqualTo("2026-10-18T14:30:00Z");
    assertThat(event.get("organizationId").asText()).isEqualTo("1001");
    assertThat(event.get("activityId").asText()).isEqualTo("3001");
    assertThat(event.get("requestId").asText()).isEqualTo("request-123");
    assertThat(event.get("payload").get("userId").isTextual()).isTrue();
    assertThat(fields(event.get("payload"))).containsExactlyInAnyOrder("userId", "notificationType",
        "title", "content", "businessType", "businessId");
    assertThat(row.getPayloadJson()).doesNotContain("password", "token", "createTime", "@class", "retryCount");
  }

  @Test
  void codecRestoresTheTypedPayloadFromEventType() {
    for (String type : DomainEventTypes.ALL) {
      DomainEventPayload payload = type.equals("checkin.completed.v1") || type.equals("checkin.corrected.v1")
          ? new CheckinStatusPayload(9L, 11L, 12L, "DYNAMIC_CODE")
          : List.of("registration.cancelled.v1", "registration.promotion-accepted.v1",
              "registration.promotion-declined.v1").contains(type)
              ? new RegistrationStatusPayload(9L, 9L, 2001L, "CANCELED", null) : intent(9L);
      DomainEventEnvelope event = envelope(type, payload);
      DomainEventEnvelope restored = codec.decode(codec.encode(event));
      assertThat(restored).isEqualTo(event);
      assertThat(restored.payload()).isExactlyInstanceOf(payload.getClass());
    }
  }

  @Test
  void registrationFactsRoundTripWithoutNotificationIntentAndRejectWrongPayload() {
    for (String type : List.of("registration.cancelled.v1", "registration.promotion-accepted.v1",
        "registration.promotion-declined.v1")) {
      var payload = new RegistrationStatusPayload(9L, 9L, 2001L, "CANCELED", "Schedule conflict");
      transaction.execute(status -> publisher.publish(command(type, payload)));
      DomainEventEnvelope restored = codec.decode(rows.get(rows.size() - 1).getPayloadJson());
      assertThat(restored.payload()).isEqualTo(payload);
      assertThat(codec.encode(restored)).doesNotContain("notificationType", "title", "content");
      assertThatThrownBy(() -> command(type, intent(9L))).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(rows).hasSize(3);
    assertThatThrownBy(() -> new RegistrationStatusPayload(9L, 9L, 0L, "CANCELED", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RegistrationStatusPayload(9L, 9L, 2001L, " ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void publishManyDeduplicatesRecipientsAndCreatesOneOutboxRowPerUser() {
    int count = transaction.execute(status -> publisher.publishMany(List.of(9L, 10L, 9L), template()));
    assertThat(count).isEqualTo(2);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(OutboxEvent::getId).doesNotHaveDuplicates();
    assertThat(rows).extracting(OutboxEvent::getEventId).doesNotHaveDuplicates();
    assertThat(rows).extracting(row -> UUID.fromString(row.getEventId())).doesNotContainNull();
    assertThat(rows).extracting(row -> ((NotificationIntentPayload) codec.decode(row.getPayloadJson()).payload()).userId())
        .containsExactly(9L, 10L);
    for (OutboxEvent row : rows) {
      assertThat(row.getTopic()).isEqualTo("registration-topic");
      assertThat(row.getPayloadJson()).doesNotContain("userIds", "recipients");
    }
  }

  @Test
  void publishesCheckinFactWithoutCreatingNotificationIntent() {
    UUID eventId = transaction.execute(status -> publisher.publish(command("checkin.completed.v1",
        new CheckinStatusPayload(9L, 11L, 12L, "MANUAL"))));
    assertThat(rows).hasSize(1);
    DomainEventEnvelope fact = codec.decode(rows.get(0).getPayloadJson());
    assertThat(fact.eventId()).isEqualTo(eventId);
    assertThat(fact.payload()).isEqualTo(new CheckinStatusPayload(9L, 11L, 12L, "MANUAL"));
    assertThat(fact.requestId()).isNull();
    assertThat(rows.get(0).getTopic()).isEqualTo("checkin-topic");
    assertThat(rows.get(0).getPayloadJson()).doesNotContain("notificationType", "title", "content");
  }

  @Test
  void rejectsUnknownOrUnversionedEventTypes() {
    String valid = codec.encode(envelope("registration.submitted.v1", intent(9L)));
    for (String type : List.of("registration.submitted", "registration.submitted.v2", "registration.unknown.v1")) {
      assertThatThrownBy(() -> codec.decode(valid.replace("registration.submitted.v1", type)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> transaction.execute(status -> publisher.publish(command(type, intent(9L)))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(rows).isEmpty();
  }

  @Test
  void rejectsWrongPayloadTypeAndMalformedOrIncompleteEnvelope() throws Exception {
    assertThatThrownBy(() -> codec.encode(envelope("checkin.completed.v1", intent(9L))))
        .isInstanceOf(IllegalArgumentException.class);
    String valid = codec.encode(envelope("registration.submitted.v1", intent(9L)));
    for (String malformed : List.of("{}", "not-json", valid + " {}",
        valid.replace("registration.submitted.v1", "checkin.completed.v1"))) {
      assertThatThrownBy(() -> codec.decode(malformed)).isInstanceOf(IllegalArgumentException.class);
    }
    for (String field : List.of("eventId", "eventType", "occurredAt", "organizationId", "aggregateType",
        "aggregateId", "activityId", "payload")) {
      ObjectNode incomplete = (ObjectNode) json.readTree(valid);
      incomplete.remove(field);
      assertThatThrownBy(() -> codec.decode(incomplete.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    ObjectNode incompletePayload = (ObjectNode) json.readTree(valid);
    ((ObjectNode) incompletePayload.get("payload")).remove("userId");
    assertThatThrownBy(() -> codec.decode(incompletePayload.toString())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresExistingTransactionForBothEntryPoints() {
    assertThatThrownBy(() -> publisher.publish(command("registration.submitted.v1", intent(9L))))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> publisher.publishMany(List.of(9L), template()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(rows).isEmpty();
  }

  @Test
  void fanoutValidatesRecipientsBeforeInsertingAndEmptyFanoutIsNoop() {
    int emptyCount = transaction.execute(status -> publisher.publishMany(List.of(), template()));
    assertThat(emptyCount).isZero();
    for (List<Long> recipients : List.of(Arrays.asList(9L, null), List.of(9L, 0L))) {
      assertThatThrownBy(() -> transaction.execute(status -> publisher.publishMany(recipients, template())))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(rows).isEmpty();
  }

  @Test
  void insertionFailurePropagatesAndRollsBackTheCallerTransaction() {
    when(mapper.insert(any(OutboxEvent.class))).thenThrow(new IllegalStateException("insert failed"));
    assertThatThrownBy(() -> transaction.execute(status -> publisher.publish(command("registration.submitted.v1", intent(9L)))))
        .isInstanceOf(IllegalStateException.class).hasMessage("insert failed");
    assertThat(transactions.rollbacks).isEqualTo(1);
    assertThat(transactions.commits).isZero();
  }

  private static DomainEventCommand command(String type, DomainEventPayload payload) {
    return new DomainEventCommand(1001L, 3001L, "REGISTRATION", "2001", type, payload);
  }

  private static NotificationIntentPayload intent(Long userId) {
    return new NotificationIntentPayload(userId, "REGISTRATION_SUBMITTED", "报名结果", "已确认报名", "REGISTRATION", 2001L);
  }

  private static NotificationEventTemplate template() {
    return new NotificationEventTemplate(1001L, 3001L, "REGISTRATION", "2001", "registration.submitted.v1",
        "REGISTRATION_SUBMITTED", "报名结果", "已确认报名", "REGISTRATION", 2001L);
  }

  private static DomainEventEnvelope envelope(String type, DomainEventPayload payload) {
    return new DomainEventEnvelope(UUID.fromString("b1445338-156d-41e3-a131-8f349282125d"), type,
        NOW, 1001L, "REGISTRATION", "2001", 3001L, "request-123", payload);
  }

  private static List<String> fields(JsonNode node) {
    List<String> result = new ArrayList<>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private static ObjectMapper configuredMapper() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(JacksonConfig.class)) {
      Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
      context.getBean(org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer.class)
          .customize(builder);
      return builder.build();
    }
  }

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    int commits;
    int rollbacks;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
    @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {}
  }
}
