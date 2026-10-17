package com.volunteerflow.infrastructure.ratelimit;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, process-local fallback used only when Redis is unavailable during login.
 *
 * <p>All mutations share one monitor so a burst cannot increment the same subject past its limit
 * unnoticed. Entries retain insertion order to make capacity eviction deterministic.
 */
public final class LocalLoginRateLimiter {

  private final Clock clock;
  private final int maxEntries;
  private final long cleanupIntervalMillis;
  private final Map<String, WindowCounter> counters = new LinkedHashMap<>();
  private long nextCleanupMillis;

  public LocalLoginRateLimiter(Clock clock, RateLimitProperties.LocalLogin settings) {
    this.clock = clock;
    this.maxEntries = settings.maxEntries();
    this.cleanupIntervalMillis = settings.cleanupInterval().toMillis();
    this.nextCleanupMillis = safeAdd(clock.millis(), cleanupIntervalMillis);
  }

  public synchronized RateLimitDecision evaluate(
      String action, String subjectHash, RateLimitProperties.Policy policy) {
    requireText(action, "action");
    requireText(subjectHash, "subjectHash");
    long now = clock.millis();
    cleanupIfDue(now);

    long windowMillis = policy.window().toMillis();
    long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
    long expiresAt = safeAdd(windowStart, windowMillis);
    String key = action + '\0' + subjectHash;
    WindowCounter counter = counters.get(key);

    if (counter == null || counter.windowStartMillis() != windowStart) {
      if (counter != null) {
        counters.remove(key);
      }
      makeRoom(now);
      counter = new WindowCounter(windowStart, expiresAt, 1);
      counters.put(key, counter);
    } else {
      counter = new WindowCounter(windowStart, expiresAt, counter.count() + 1);
      counters.put(key, counter);
    }

    long remainingMillis = Math.max(1L, expiresAt - now);
    long retryAfterSeconds =
        Math.max(
            1L,
            remainingMillis / 1_000L + (remainingMillis % 1_000L == 0 ? 0 : 1));
    return new RateLimitDecision(counter.count() <= policy.limit(), retryAfterSeconds);
  }

  synchronized int trackedSubjectCount() {
    return counters.size();
  }

  private void cleanupIfDue(long now) {
    if (now < nextCleanupMillis) {
      return;
    }
    removeExpired(now);
    nextCleanupMillis = safeAdd(now, cleanupIntervalMillis);
  }

  private void makeRoom(long now) {
    if (counters.size() < maxEntries) {
      return;
    }
    removeExpired(now);
    if (counters.size() >= maxEntries) {
      Iterator<String> oldest = counters.keySet().iterator();
      if (oldest.hasNext()) {
        oldest.next();
        oldest.remove();
      }
    }
  }

  private void removeExpired(long now) {
    counters.values().removeIf(counter -> counter.expiresAtMillis() <= now);
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private record WindowCounter(long windowStartMillis, long expiresAtMillis, int count) {}
}
