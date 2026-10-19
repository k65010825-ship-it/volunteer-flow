package com.volunteerflow.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class Stage5KafkaOutboxIntegrationTest extends Stage5IntegrationSupport {
  @Test
  void registrationPublishesConsumesOnceAndDuplicateDoesNotCreateAnotherNotification() throws Exception {
    var registration = submit(101L);
    String eventId = eventId(registration.registrationId());
    drain(eventId);
    assertThat(count("notification", "user_id=101")).isEqualTo(1);
    assertThat(count("consumed_event", "event_id='" + eventId + "'")).isEqualTo(1);
    double before = counter("kafka.consumer.duplicate");
    var row = outbox.selectById(outboxId(eventId));
    kafka.send(row.getTopic(), row.getMessageKey(), row.getPayloadJson()).get();
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(counter("kafka.consumer.duplicate")).isGreaterThan(before));
    assertThat(count("notification", "user_id=101")).isEqualTo(1);
    assertThat(counter("outbox.publish.success")).isPositive();
    assertThat(counter("kafka.consumer.success")).isPositive();
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(meters.getMeters())
        .anySatisfy(m -> assertThat(m.getId().getName()).contains("records.lag")));
  }

  @Test
  void outboxInsertFailureRollsBackRegistrationCycleAndAudit() {
    jdbc.execute("CREATE TRIGGER task8_reject_outbox BEFORE INSERT ON outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TASK8_INJECTED_FAILURE'");
    try {
      assertThatThrownBy(() -> submit(102L)).isInstanceOf(RuntimeException.class);
      assertThat(count("registration", "user_id=102")).isZero();
      assertThat(count("registration_cycle", "user_id=102")).isZero();
      assertThat(count("audit_log", "actor_user_id=102")).isZero();
      assertThat(count("outbox_event", "1=1")).isZero();
    } finally { jdbc.execute("DROP TRIGGER task8_reject_outbox"); }
  }

  @Test
  void notificationInsertFailureRollsBackConsumedMarkerAndCanThenRetry() {
    var registration = submit(103L);
    var event = codec.decode(outbox.selectById(outboxId(eventId(registration.registrationId()))).getPayloadJson());
    jdbc.execute("CREATE TRIGGER task8_reject_notification BEFORE INSERT ON notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TASK8_INJECTED_FAILURE'");
    try {
      assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);
      assertThat(count("consumed_event", "event_id='" + event.eventId() + "'")).isZero();
      assertThat(count("notification", "user_id=103")).isZero();
    } finally { jdbc.execute("DROP TRIGGER task8_reject_notification"); }
    handler.handle(event);
    assertThat(count("consumed_event", "event_id='" + event.eventId() + "'")).isEqualTo(1);
    assertThat(count("notification", "user_id=103")).isEqualTo(1);
  }

  @Test
  void mysqlUsesIndexedBoundedDueLeaseAndRetentionPathsAndSkipsContendedRows() throws Exception {
    assertIndexedPlansAndContention();
  }

  @Test
  void malformedRecordRetriesThenReachesDltWithoutCreatingDatabaseSideEffects() throws Exception {
    assertDlt();
  }
}
