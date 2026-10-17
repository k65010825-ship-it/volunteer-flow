package com.volunteerflow.infrastructure.ratelimit;

public interface RateLimitStore {
  RateLimitDecision evaluate(
      String action, String subjectHash, RateLimitProperties.Policy policy);
}
