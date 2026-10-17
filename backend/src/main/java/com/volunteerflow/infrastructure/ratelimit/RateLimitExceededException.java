package com.volunteerflow.infrastructure.ratelimit;

public final class RateLimitExceededException extends RuntimeException {

  private final long retryAfterSeconds;

  public RateLimitExceededException(long retryAfterSeconds) {
    super("Too many requests");
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
