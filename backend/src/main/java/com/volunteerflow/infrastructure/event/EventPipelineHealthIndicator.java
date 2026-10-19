package com.volunteerflow.infrastructure.event;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Last observed relay result, not a broker connectivity probe or a core readiness dependency. */
@Component("eventPipeline")
@Profile("!test")
public class EventPipelineHealthIndicator implements HealthIndicator {
  private final EventPipelineMetrics metrics;

  public EventPipelineHealthIndicator(EventPipelineMetrics metrics) { this.metrics = metrics; }

  @Override
  public Health health() {
    var state = metrics.publishState();
    return Health.status(state.name()).withDetail("diagnosticOnly", true)
        .withDetail("evidence", "LAST_RELAY_RESULT")
        .withDetail("category", switch (state) {
          case UNKNOWN -> "NO_PUBLISH_OBSERVED";
          case UP -> "LAST_PUBLISH_SUCCEEDED";
          case DOWN -> "BROKER_PUBLISH_FAILED";
        }).build();
  }
}
