package com.volunteerflow.infrastructure.event;

public record DomainEventCommand(Long organizationId, Long activityId, String aggregateType,
    String aggregateId, String eventType, DomainEventPayload payload) {
  public DomainEventCommand {
    DomainEventTypes.validateContext(organizationId, activityId, aggregateType, aggregateId);
    DomainEventTypes.validatePayload(eventType, payload);
  }
}
