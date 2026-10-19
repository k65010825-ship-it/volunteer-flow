package com.volunteerflow.infrastructure.event;

/** Activity lifecycle fact without a recipient or an in-app notification request. */
public record ActivityStatusPayload(Long actorId, String status) implements DomainEventPayload {
  public ActivityStatusPayload {
    DomainEventTypes.positiveId(actorId, "actorId");
    DomainEventTypes.text(status, "status");
  }
}
