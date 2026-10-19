package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EventTopicRouterTest {
  private static final Map<String, String> CATALOG = Map.ofEntries(
      Map.entry("activity.published.v1", "activity"),
      Map.entry("activity.changed.v1", "activity"),
      Map.entry("activity.cancelled.v1", "activity"),
      Map.entry("registration.submitted.v1", "registration"),
      Map.entry("registration.reviewed.v1", "registration"),
      Map.entry("registration.cancelled.v1", "registration"),
      Map.entry("registration.promotion-offered.v1", "registration"),
      Map.entry("registration.promotion-accepted.v1", "registration"),
      Map.entry("registration.promotion-declined.v1", "registration"),
      Map.entry("registration.promotion-expired.v1", "registration"),
      Map.entry("checkin.opened.v1", "checkin"),
      Map.entry("checkin.closed.v1", "checkin"),
      Map.entry("checkin.completed.v1", "checkin"),
      Map.entry("checkin.corrected.v1", "checkin"));

  @Test
  void routesEveryCatalogEventToExactlyOneConfiguredTopic() {
    assertThat(DomainEventTypes.ALL).containsExactlyInAnyOrderElementsOf(CATALOG.keySet());
    new ApplicationContextRunner().withUserConfiguration(EventTopicRouter.class).run(context -> {
      EventTopicRouter router = context.getBean(EventTopicRouter.class);
      CATALOG.forEach((type, family) ->
          assertThat(router.topicFor(type)).isEqualTo("volunteerflow." + family + "-events.v1"));
    });
    new ApplicationContextRunner().withUserConfiguration(EventTopicRouter.class)
        .withPropertyValues("volunteerflow.events.topics.activity=custom.activity",
            "volunteerflow.events.topics.registration=custom.registration",
            "volunteerflow.events.topics.checkin=custom.checkin")
        .run(context -> CATALOG.forEach((type, family) ->
            assertThat(context.getBean(EventTopicRouter.class).topicFor(type))
                .isEqualTo("custom." + family)));
  }

  @Test
  void usesOrganizationAndActivityAsMessageKey() {
    EventTopicRouter router = new EventTopicRouter("a", "r", "c");
    assertThat(router.messageKey(1001L, 3001L)).isEqualTo("1001:3001");
    assertThat(router.messageKey(1002L, 3001L)).isEqualTo("1002:3001");
    assertThatThrownBy(() -> router.messageKey(null, 3001L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> router.messageKey(1001L, 0L)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownOrUnversionedEventTypes() {
    EventTopicRouter router = new EventTopicRouter("a", "r", "c");
    for (String type : new String[] {"registration.submitted", "registration.submitted.v2",
        "activity.unknown.v1", "checkin.completed.v10", "", null}) {
      assertThatThrownBy(() -> router.topicFor(type)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsInvalidTopicConfiguration() {
    for (String topic : new String[] {"", "bad topic", "..", "x".repeat(161), null}) {
      assertThatThrownBy(() -> new EventTopicRouter(topic, "r", "c"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new EventTopicRouter("same", "same", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
