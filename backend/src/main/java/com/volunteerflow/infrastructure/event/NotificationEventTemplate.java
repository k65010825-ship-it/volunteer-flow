package com.volunteerflow.infrastructure.event;

/** Command context and immutable notification data, without a recipient list. */
public record NotificationEventTemplate(Long organizationId, Long activityId, String aggregateType,
    String aggregateId, String eventType, String notificationType, String title, String content,
    String businessType, Long businessId) {
  public NotificationEventTemplate {
    DomainEventTypes.validateContext(organizationId, activityId, aggregateType, aggregateId);
    if (DomainEventTypes.payloadType(eventType) != NotificationIntentPayload.class) {
      throw new IllegalArgumentException("Fan-out requires a notification event contract");
    }
    DomainEventTypes.text(notificationType, "notificationType");
    DomainEventTypes.text(title, "title");
    DomainEventTypes.text(content, "content");
    DomainEventTypes.text(businessType, "businessType");
    DomainEventTypes.positiveId(businessId, "businessId");
  }

  public DomainEventCommand forUser(Long userId) {
    return new DomainEventCommand(organizationId, activityId, aggregateType, aggregateId, eventType,
        new NotificationIntentPayload(userId, notificationType, title, content, businessType, businessId));
  }
}
