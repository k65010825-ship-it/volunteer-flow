package com.volunteerflow.infrastructure.ratelimit;

public final class RateLimitStoreUnavailableException extends RuntimeException {

  public RateLimitStoreUnavailableException() {
    super("Rate-limit store is unavailable");
  }

  public RateLimitStoreUnavailableException(Throwable cause) {
    super("Rate-limit store is unavailable", cause);
  }
}
