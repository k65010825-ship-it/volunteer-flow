package com.volunteerflow.infrastructure.event;

import java.util.Set;

/** Exact wire contracts. A new version requires an explicit catalog/codec change. */
public final class DomainEventTypes {
  public static final String ACTIVITY_PUBLISHED = "activity.published.v1";
  public static final String ACTIVITY_CHANGED = "activity.changed.v1";
  public static final String ACTIVITY_CANCELLED = "activity.cancelled.v1";
  public static final String REGISTRATION_SUBMITTED = "registration.submitted.v1";
  public static final String REGISTRATION_REVIEWED = "registration.reviewed.v1";
  public static final String REGISTRATION_CANCELLED = "registration.cancelled.v1";
  public static final String REGISTRATION_PROMOTION_OFFERED = "registration.promotion-offered.v1";
  public static final String REGISTRATION_PROMOTION_ACCEPTED = "registration.promotion-accepted.v1";
  public static final String REGISTRATION_PROMOTION_DECLINED = "registration.promotion-declined.v1";
  public static final String REGISTRATION_PROMOTION_EXPIRED = "registration.promotion-expired.v1";
  public static final String REGISTRATION_PROMOTION_ADMIN_ACTION_REQUIRED =
      "registration.promotion-admin-action-required.v1";
  public static final String CHECKIN_OPENED = "checkin.opened.v1";
  public static final String CHECKIN_CLOSED = "checkin.closed.v1";
  public static final String CHECKIN_COMPLETED = "checkin.completed.v1";
  public static final String CHECKIN_CORRECTED = "checkin.corrected.v1";

  public static final Set<String> ALL = Set.of(ACTIVITY_PUBLISHED, ACTIVITY_CHANGED,
      ACTIVITY_CANCELLED, REGISTRATION_SUBMITTED, REGISTRATION_REVIEWED, REGISTRATION_CANCELLED,
      REGISTRATION_PROMOTION_OFFERED, REGISTRATION_PROMOTION_ACCEPTED,
      REGISTRATION_PROMOTION_DECLINED, REGISTRATION_PROMOTION_EXPIRED,
      REGISTRATION_PROMOTION_ADMIN_ACTION_REQUIRED,
      CHECKIN_OPENED, CHECKIN_CLOSED, CHECKIN_COMPLETED, CHECKIN_CORRECTED);

  private DomainEventTypes() {}

  public static Class<? extends DomainEventPayload> payloadType(String eventType) {
    if (eventType == null || !ALL.contains(eventType)) {
      throw new IllegalArgumentException("Unsupported event type/version");
    }
    if (Set.of(REGISTRATION_CANCELLED, REGISTRATION_PROMOTION_ACCEPTED,
        REGISTRATION_PROMOTION_DECLINED).contains(eventType)) {
      return RegistrationStatusPayload.class;
    }
    if (ACTIVITY_PUBLISHED.equals(eventType)) return ActivityStatusPayload.class;
    if (CHECKIN_CLOSED.equals(eventType)) return CheckinSessionStatusPayload.class;
    return CHECKIN_COMPLETED.equals(eventType) || CHECKIN_CORRECTED.equals(eventType)
        ? CheckinStatusPayload.class : NotificationIntentPayload.class;
  }

  static void validatePayload(String eventType, DomainEventPayload payload) {
    if (!payloadType(eventType).isInstance(payload)) {
      throw new IllegalArgumentException("Payload does not match event contract");
    }
  }

  static void validateContext(Long organizationId, Long activityId, String aggregateType, String aggregateId) {
    positiveId(organizationId, "organizationId");
    positiveId(activityId, "activityId");
    text(aggregateType, "aggregateType");
    text(aggregateId, "aggregateId");
  }

  static void positiveId(Long value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  static void text(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
