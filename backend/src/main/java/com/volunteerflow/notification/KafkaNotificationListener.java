package com.volunteerflow.notification;

import com.volunteerflow.infrastructure.event.DomainEventCodec;
import com.volunteerflow.infrastructure.event.DomainEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class KafkaNotificationListener {
  private final DomainEventCodec codec;
  private final NotificationEventHandler handler;

  public KafkaNotificationListener(DomainEventCodec codec, NotificationEventHandler handler) {
    this.codec = codec;
    this.handler = handler;
  }

  @KafkaListener(id = "notification-events", containerFactory = "notificationKafkaListenerContainerFactory",
      groupId = "${volunteerflow.events.consumer-group:volunteerflow-notification-v1}",
      autoStartup = "${volunteerflow.events.consumer-auto-startup:true}",
      topics = {"${volunteerflow.events.topics.activity:volunteerflow.activity-events.v1}",
          "${volunteerflow.events.topics.registration:volunteerflow.registration-events.v1}",
          "${volunteerflow.events.topics.checkin:volunteerflow.checkin-events.v1}"})
  public void onMessage(ConsumerRecord<String, String> record) {
    DomainEventEnvelope event;
    try {
      event = codec.decode(record.value());
    } catch (RuntimeException failure) {
      throw new ProcessingFailure("INVALID_EVENT_CONTRACT");
    }
    try {
      handler.handle(event);
    } catch (RuntimeException failure) {
      // SQL exceptions may contain credentials or payloads. Never let them reach Kafka logging.
      throw new ProcessingFailure("NOTIFICATION_PROCESSING_FAILED");
    }
    // RECORD mode acknowledges only after this call (and the handler's transaction) returns.
  }

  /** Safe, stackless boundary: no raw exception/cause can enter framework logs or headers. */
  static final class ProcessingFailure extends RuntimeException {
    ProcessingFailure(String category) { super(category, null, false, false); }
  }
}
