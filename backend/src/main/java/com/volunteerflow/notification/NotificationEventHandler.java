package com.volunteerflow.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.infrastructure.event.ConsumedEvent;
import com.volunteerflow.infrastructure.event.ConsumedEventMapper;
import com.volunteerflow.infrastructure.event.DomainEventEnvelope;
import com.volunteerflow.infrastructure.event.DomainEventTypes;
import com.volunteerflow.infrastructure.event.NotificationIntentPayload;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class NotificationEventHandler {
  private static final String CONSUMER_NAME = "volunteerflow-notification-v1";
  private final ConsumedEventMapper consumed;
  private final NotificationService notifications;
  private final Clock clock;

  public NotificationEventHandler(ConsumedEventMapper consumed, NotificationService notifications, Clock clock) {
    this.consumed = consumed;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Invoked through the Spring proxy; returning means marker and sink have committed together. */
  @Transactional
  public ProcessingResult handle(DomainEventEnvelope event) {
    if (event == null || !DomainEventTypes.payloadType(event.eventType()).isInstance(event.payload())) {
      throw new IllegalArgumentException("Invalid event contract");
    }
    ConsumedEvent marker = new ConsumedEvent();
    marker.setId(IdWorker.getId());
    marker.setConsumerName(CONSUMER_NAME);
    marker.setEventId(event.eventId().toString());
    marker.setProcessedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    if (consumed.insertIfAbsent(marker) == 0) {
      return ProcessingResult.DUPLICATE;
    }
    if (event.payload() instanceof NotificationIntentPayload intent) {
      notifications.writeEvent(event.eventId(), event.organizationId(), intent);
      return ProcessingResult.CREATED;
    }
    return ProcessingResult.NO_NOTIFICATION;
  }

  public enum ProcessingResult { CREATED, NO_NOTIFICATION, DUPLICATE }
}
