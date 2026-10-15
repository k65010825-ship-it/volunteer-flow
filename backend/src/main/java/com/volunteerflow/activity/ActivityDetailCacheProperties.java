package com.volunteerflow.activity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime policy for the optional published-activity detail cache. */
@Component
@ConfigurationProperties(prefix = "volunteerflow.cache.activity-detail")
public record ActivityDetailCacheProperties(Duration ttl, Duration maxJitter) {

  public ActivityDetailCacheProperties {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException(
          "volunteerflow.cache.activity-detail.ttl must be positive");
    }
    if (maxJitter == null || maxJitter.isNegative()) {
      throw new IllegalArgumentException(
          "volunteerflow.cache.activity-detail.max-jitter must not be negative");
    }
  }
}
