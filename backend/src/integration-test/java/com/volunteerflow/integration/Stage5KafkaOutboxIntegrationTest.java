package com.volunteerflow.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.volunteerflow.infrastructure.event.Stage5SqlFailureAssertions;
import com.volunteerflow.notification.NotificationEventHandler;
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
    assertTriggerCount("task8_reject_outbox", 0);
    jdbc.execute("CREATE TRIGGER task8_reject_outbox BEFORE INSERT ON outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TASK8_INJECTED_FAILURE'");
    try {
      assertTriggerCount("task8_reject_outbox", 1);
      Stage5SqlFailureAssertions.assertInjectedFailure(catchThrowable(() -> submit(102L)));
      assertThat(count("registration", "user_id=102")).isZero();
      assertThat(count("registration_cycle", "user_id=102")).isZero();
      assertThat(count("audit_log", "actor_user_id=102")).isZero();
      assertThat(count("outbox_event", "1=1")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER task8_reject_outbox");
      assertTriggerCount("task8_reject_outbox", 0);
    }
    // Same request after checked cleanup must reach the normal business path, not fail authorization early.
    var retry = submit(102L);
    assertThat(retry.status()).isEqualTo("CONFIRMED");
    assertThat(count("registration", "user_id=102")).isEqualTo(1);
    assertThat(count("registration_cycle", "user_id=102 AND status='CONFIRMED'")).isEqualTo(1);
    assertThat(count("audit_log", "actor_user_id=102 AND action='registration.submitted'")).isEqualTo(1);
    assertThat(count("outbox_event", "aggregate_id='" + retry.registrationId() + "' AND status='PENDING'")).isEqualTo(1);
  }

  @Test
  void notificationInsertFailureRollsBackConsumedMarkerAndCanThenRetry() {
    var registration = submit(103L);
    var event = codec.decode(outbox.selectById(outboxId(eventId(registration.registrationId()))).getPayloadJson());
    assertTriggerCount("task8_reject_notification", 0);
    jdbc.execute("CREATE TRIGGER task8_reject_notification BEFORE INSERT ON notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TASK8_INJECTED_FAILURE'");
    try {
      assertTriggerCount("task8_reject_notification", 1);
      Stage5SqlFailureAssertions.assertInjectedFailure(catchThrowable(() -> handler.handle(event)));
      assertThat(count("consumed_event", "event_id='" + event.eventId() + "'")).isZero();
      assertThat(count("notification", "user_id=103")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER task8_reject_notification");
      assertTriggerCount("task8_reject_notification", 0);
    }
    assertThat(handler.handle(event)).isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
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

  private void assertTriggerCount(String name, long expected) {
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.triggers
        WHERE trigger_schema=DATABASE() AND trigger_name=?
        """, Long.class, name)).isEqualTo(expected);
  }
}
