package com.volunteerflow.notification;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaNotificationListenerTest {
  @Test
  void duplicateEventIsAcknowledgedWithoutSecondNotification() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    KafkaNotificationListener listener = new KafkaNotificationListener(f.codec, f.handler);
    ConsumerRecord<String, String> record = record(f.codec.encode(f.event()));
    listener.onMessage(record);
    assertThat(f.transactions.commits).isEqualTo(1); // Returning permits RECORD acknowledgement after commit.
    listener.onMessage(record);
    assertThat(f.transactions.commits).isEqualTo(2);
    assertThat(f.markers).hasSize(1);
    assertThat(f.rows).hasSize(1);
  }

  @Test
  void unsupportedVersionDoesNotInsertConsumedMarker() {
    for (String type : List.of("registration.submitted", "registration.submitted.v2", "unknown.fact.v1")) {
      NotificationConsumerFixture f = new NotificationConsumerFixture();
      KafkaNotificationListener listener = new KafkaNotificationListener(f.codec, f.handler);
      String json = f.codec.encode(f.event()).replace("registration.submitted.v1", type);
      assertThatThrownBy(() -> listener.onMessage(record(json))).hasMessage("INVALID_EVENT_CONTRACT").hasNoCause();
      assertThat(f.markers).isEmpty();
      assertThat(f.rows).isEmpty();
      assertThat(f.transactions.commits).isZero();
    }
  }

  @Test
  void malformedPayloadNeverRecordsConsumption() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    KafkaNotificationListener listener = new KafkaNotificationListener(f.codec, f.handler);
    String valid = f.codec.encode(f.event());
    for (String json : List.of("not-json", "{}", valid + " {}", valid.replace("\"userId\":9", "\"userId\":0"))) {
      assertThatThrownBy(() -> listener.onMessage(record(json))).hasMessage("INVALID_EVENT_CONTRACT").hasNoCause();
    }
    assertThatThrownBy(() -> listener.onMessage(record(null))).hasMessage("INVALID_EVENT_CONTRACT");
    assertThat(f.markers).isEmpty();
    assertThat(f.rows).isEmpty();
  }

  @Test
  void databaseFailureEscapesOnlyAsSafeCategoryAndDoesNotPermitAcknowledgement() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    f.failNotification = true;
    KafkaNotificationListener listener = new KafkaNotificationListener(f.codec, f.handler);
    Throwable failure = catchThrowable(() -> listener.onMessage(record(f.codec.encode(f.event()))));
    assertThat(failure).hasMessage("NOTIFICATION_PROCESSING_FAILED").hasNoCause();
    assertThat(failure.getStackTrace()).isEmpty();
    assertThat(f.transactions.rollbacks).isEqualTo(1);
    assertThat(f.markers).isEmpty();
    assertThat(f.rows).isEmpty();
  }

  static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>("volunteerflow.registration-events.v1", 2, 41L, "1001:3001", value);
  }
}
