package com.volunteerflow.infrastructure.event;

/** Session lifecycle fact; closing a session has never requested a notification. */
public record CheckinSessionStatusPayload(Long actorId, Long sessionId, String status)
    implements DomainEventPayload {
  public CheckinSessionStatusPayload {
    DomainEventTypes.positiveId(actorId, "actorId");
    DomainEventTypes.positiveId(sessionId, "sessionId");
    DomainEventTypes.text(status, "status");
  }
}
