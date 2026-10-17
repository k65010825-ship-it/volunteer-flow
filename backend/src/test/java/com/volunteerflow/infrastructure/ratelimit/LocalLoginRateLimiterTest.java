package com.volunteerflow.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalLoginRateLimiterTest {

  private final ExecutorService executor = Executors.newFixedThreadPool(16);
  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(12_345));

  @AfterEach
  void shutDownExecutor() {
    executor.shutdownNow();
  }

  @Test
  void allowsExactlyTheLimitThenRejects() {
    LocalLoginRateLimiter limiter = limiter(100, 60);
    RateLimitProperties.Policy policy = policy(3, 10);

    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isTrue();
    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isTrue();
    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isTrue();
    RateLimitDecision rejected = limiter.evaluate("login-ip", "subject", policy);

    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void resetsAtExactWindowBoundary() {
    LocalLoginRateLimiter limiter = limiter(100, 60);
    RateLimitProperties.Policy policy = policy(1, 10);
    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isTrue();
    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isFalse();

    clock.set(Instant.ofEpochMilli(20_000));

    assertThat(limiter.evaluate("login-ip", "subject", policy).allowed()).isTrue();
  }

  @Test
  void concurrentAttemptsDoNotOvershootLimit() throws Exception {
    LocalLoginRateLimiter limiter = limiter(100, 60);
    RateLimitProperties.Policy policy = policy(10, 60);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> attempts = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      attempts.add(
          executor.submit(
              () -> {
                start.await();
                return limiter.evaluate("login-username", "same", policy).allowed();
              }));
    }

    start.countDown();
    int allowed = 0;
    for (Future<Boolean> attempt : attempts) {
      if (attempt.get()) {
        allowed++;
      }
    }

    assertThat(allowed).isEqualTo(10);
  }

  @Test
  void expiredEntriesAreRemovedDuringCleanup() {
    LocalLoginRateLimiter limiter = limiter(10, 1);
    RateLimitProperties.Policy policy = policy(3, 2);
    limiter.evaluate("login-ip", "first", policy);
    limiter.evaluate("login-ip", "second", policy);
    assertThat(limiter.trackedSubjectCount()).isEqualTo(2);

    clock.set(Instant.ofEpochMilli(15_000));
    limiter.evaluate("login-ip", "third", policy);

    assertThat(limiter.trackedSubjectCount()).isEqualTo(1);
  }

  @Test
  void capacityNeverExceedsConfiguredMaximumUnderConcurrentSubjects() throws Exception {
    LocalLoginRateLimiter limiter = limiter(50, 60);
    RateLimitProperties.Policy policy = policy(3, 60);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> attempts = new ArrayList<>();
    for (int index = 0; index < 200; index++) {
      int subject = index;
      attempts.add(
          executor.submit(
              () -> {
                start.await();
                limiter.evaluate("login-ip", "subject-" + subject, policy);
                return null;
              }));
    }

    start.countDown();
    for (Future<?> attempt : attempts) {
      attempt.get();
    }

    assertThat(limiter.trackedSubjectCount()).isLessThanOrEqualTo(50);
  }

  private LocalLoginRateLimiter limiter(int maxEntries, long cleanupSeconds) {
    return new LocalLoginRateLimiter(
        clock, new RateLimitProperties.LocalLogin(maxEntries, Duration.ofSeconds(cleanupSeconds)));
  }

  private RateLimitProperties.Policy policy(int limit, long windowSeconds) {
    return new RateLimitProperties.Policy(limit, Duration.ofSeconds(windowSeconds));
  }

  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
