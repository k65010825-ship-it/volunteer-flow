package com.volunteerflow.infrastructure.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "volunteerflow.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    String keySecret,
    LoginPolicies login,
    Policy registration,
    Policy promotionResponse,
    Policy staticCheckin,
    Policy dynamicCheckin,
    LocalLogin localLogin) {

  public RateLimitProperties {
    keySecret = keySecret == null ? "" : keySecret;
    login = Objects.requireNonNull(login, "login must not be null");
    registration = Objects.requireNonNull(registration, "registration must not be null");
    promotionResponse =
        Objects.requireNonNull(promotionResponse, "promotionResponse must not be null");
    staticCheckin = Objects.requireNonNull(staticCheckin, "staticCheckin must not be null");
    dynamicCheckin = Objects.requireNonNull(dynamicCheckin, "dynamicCheckin must not be null");
    localLogin = Objects.requireNonNull(localLogin, "localLogin must not be null");

    if (enabled && keySecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("keySecret must contain at least 32 UTF-8 bytes");
    }
    int largestLoginLimit = Math.max(login.ip().limit(), login.username().limit());
    if (localLogin.maxEntries() < largestLoginLimit) {
      throw new IllegalArgumentException("localLogin.maxEntries must cover the login limits");
    }
  }

  public record Policy(int limit, Duration window) {
    public Policy {
      if (limit <= 0) {
        throw new IllegalArgumentException("limit must be positive");
      }
      if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException("window must be at least one second");
      }
    }
  }

  public record LoginPolicies(Policy ip, Policy username) {
    public LoginPolicies {
      ip = Objects.requireNonNull(ip, "ip policy must not be null");
      username = Objects.requireNonNull(username, "username policy must not be null");
    }
  }

  public record LocalLogin(int maxEntries, Duration cleanupInterval) {
    public LocalLogin {
      if (maxEntries <= 0) {
        throw new IllegalArgumentException("maxEntries must be positive");
      }
      if (cleanupInterval == null || cleanupInterval.compareTo(Duration.ofSeconds(1)) < 0) {
        throw new IllegalArgumentException("cleanupInterval must be at least one second");
      }
    }
  }
}
