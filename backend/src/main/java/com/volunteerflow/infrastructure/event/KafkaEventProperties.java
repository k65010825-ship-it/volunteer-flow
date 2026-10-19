package com.volunteerflow.infrastructure.event;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("volunteerflow.events")
public class KafkaEventProperties {
  private String bootstrapServers = "localhost:9092";
  private String clientId = "volunteerflow-outbox";
  private String consumerGroup = "volunteerflow-notification-v1";
  private Duration pollInterval = Duration.ofSeconds(1);
  private int batchSize = 100;
  private Duration lease = Duration.ofSeconds(60);
  private Duration sendTimeout = Duration.ofSeconds(10);
  private Duration retryInitial = Duration.ofSeconds(5);
  private Duration retryMax = Duration.ofMinutes(5);
  private Duration retention = Duration.ofDays(7);
  private Duration retentionInterval = Duration.ofHours(1);
  private int retentionBatchSize = 100;

  @PostConstruct
  void validate() {
    for (Duration duration : new Duration[]{pollInterval, lease, sendTimeout, retryInitial, retryMax, retention, retentionInterval}) {
      if (duration == null || duration.toMillis() < 1 || duration.compareTo(Duration.ofDays(3650)) > 0) {
        throw new IllegalArgumentException("Event durations must be positive and bounded");
      }
    }
    if (batchSize < 1 || batchSize > 10000 || retentionBatchSize < 1 || retentionBatchSize > 10000
        || retryInitial.compareTo(retryMax) > 0 || lease.compareTo(sendTimeout.multipliedBy(2)) <= 0
        || sendTimeout.toMillis() > Integer.MAX_VALUE
        || bootstrapServers == null || bootstrapServers.isBlank() || clientId == null || clientId.isBlank()) {
      throw new IllegalArgumentException("Invalid event relay configuration");
    }
  }
}
