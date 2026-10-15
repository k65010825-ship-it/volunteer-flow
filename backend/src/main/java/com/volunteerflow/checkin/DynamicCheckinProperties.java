package com.volunteerflow.checkin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("volunteerflow.checkin.dynamic-code")
public record DynamicCheckinProperties(Duration window, Duration ttl, int tokenBytes) {
  public DynamicCheckinProperties {
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("Dynamic check-in window must be positive");
    }
    if (ttl == null || ttl.compareTo(window) <= 0) {
      throw new IllegalArgumentException("Dynamic check-in TTL must be greater than its window");
    }
    if (tokenBytes < 16) {
      throw new IllegalArgumentException("Dynamic check-in token must contain at least 16 bytes");
    }
  }
}
