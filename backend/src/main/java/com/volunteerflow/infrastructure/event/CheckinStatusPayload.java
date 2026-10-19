package com.volunteerflow.infrastructure.event;

/** Completion/correction fact; it does not request an in-app notification. */
public record CheckinStatusPayload(Long userId, Long sessionId, Long checkinRecordId,
    String method) implements DomainEventPayload {
  public CheckinStatusPayload {
    DomainEventTypes.positiveId(userId, "userId");
    DomainEventTypes.positiveId(sessionId, "sessionId");
    DomainEventTypes.positiveId(checkinRecordId, "checkinRecordId");
    DomainEventTypes.text(method, "method");
  }
}
