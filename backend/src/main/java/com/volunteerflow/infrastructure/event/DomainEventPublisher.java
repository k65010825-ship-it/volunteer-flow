package com.volunteerflow.infrastructure.event;

import java.util.Collection;
import java.util.UUID;

/** Inserts into the caller's business transaction; never contacts Kafka. */
public interface DomainEventPublisher {
  UUID publish(DomainEventCommand command);

  int publishMany(Collection<Long> userIds, NotificationEventTemplate template);
}
