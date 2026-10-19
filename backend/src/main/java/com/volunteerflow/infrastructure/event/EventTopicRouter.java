package com.volunteerflow.infrastructure.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EventTopicRouter {
  private final String activityTopic;
  private final String registrationTopic;
  private final String checkinTopic;

  public EventTopicRouter(
      @Value("${volunteerflow.events.topics.activity:volunteerflow.activity-events.v1}") String activityTopic,
      @Value("${volunteerflow.events.topics.registration:volunteerflow.registration-events.v1}") String registrationTopic,
      @Value("${volunteerflow.events.topics.checkin:volunteerflow.checkin-events.v1}") String checkinTopic) {
    validateTopic(activityTopic);
    validateTopic(registrationTopic);
    validateTopic(checkinTopic);
    if (activityTopic.equals(registrationTopic) || activityTopic.equals(checkinTopic)
        || registrationTopic.equals(checkinTopic)) {
      throw new IllegalArgumentException("Event topics must be distinct");
    }
    this.activityTopic = activityTopic;
    this.registrationTopic = registrationTopic;
    this.checkinTopic = checkinTopic;
  }

  public String topicFor(String eventType) {
    DomainEventTypes.payloadType(eventType); // Reject unknown/unversioned types before routing.
    if (eventType.startsWith("activity.")) {
      return activityTopic;
    }
    return eventType.startsWith("registration.") ? registrationTopic : checkinTopic;
  }

  public String messageKey(Long organizationId, Long activityId) {
    DomainEventTypes.positiveId(organizationId, "organizationId");
    DomainEventTypes.positiveId(activityId, "activityId");
    return organizationId + ":" + activityId;
  }

  private static void validateTopic(String topic) {
    // The DB column is narrower than Kafka's 249-character limit.
    if (topic == null || topic.length() > 160 || !topic.matches("[A-Za-z0-9._-]+")
        || topic.equals(".") || topic.equals("..")) {
      throw new IllegalArgumentException("Invalid event topic configuration");
    }
  }
}
