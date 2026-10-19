package com.volunteerflow.infrastructure.event;

public record NotificationIntentPayload(Long userId, String notificationType, String title,
    String content, String businessType, Long businessId) implements DomainEventPayload {
  public NotificationIntentPayload {
    DomainEventTypes.positiveId(userId, "userId");
    DomainEventTypes.text(notificationType, "notificationType");
    DomainEventTypes.text(title, "title");
    DomainEventTypes.text(content, "content");
    DomainEventTypes.text(businessType, "businessType");
    DomainEventTypes.positiveId(businessId, "businessId");
  }
}
