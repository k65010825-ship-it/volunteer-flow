package com.volunteerflow.infrastructure.event;

/** A registration state fact, deliberately without a notification intent or display text. */
public record RegistrationStatusPayload(Long actorId, Long userId, Long registrationId,
    String status, String reason) implements DomainEventPayload {
  public RegistrationStatusPayload {
    DomainEventTypes.positiveId(actorId, "actorId");
    DomainEventTypes.positiveId(userId, "userId");
    DomainEventTypes.positiveId(registrationId, "registrationId");
    DomainEventTypes.text(status, "status");
  }
}
