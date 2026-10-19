package com.volunteerflow.infrastructure.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

public record DomainEventEnvelope(UUID eventId, String eventType,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
    Long organizationId, String aggregateType, String aggregateId, Long activityId,
    String requestId, DomainEventPayload payload) {
  public DomainEventEnvelope {
    if (eventId == null || occurredAt == null) {
      throw new IllegalArgumentException("eventId and occurredAt are required");
    }
    DomainEventTypes.validateContext(organizationId, activityId, aggregateType, aggregateId);
    DomainEventTypes.validatePayload(eventType, payload);
  }
}
