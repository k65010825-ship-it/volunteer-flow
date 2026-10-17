package com.volunteerflow.infrastructure.ratelimit;

import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

public final class RedisFixedWindowRateLimiter implements RateLimitStore {

  private static final DefaultRedisScript<List> SCRIPT = script();

  private final StringRedisTemplate redis;
  private final RedisKeyFactory keys;
  private final Clock clock;

  public RedisFixedWindowRateLimiter(
      StringRedisTemplate redis, RedisKeyFactory keys, Clock clock) {
    this.redis = redis;
    this.keys = keys;
    this.clock = clock;
  }

  @Override
  public RateLimitDecision evaluate(
      String action, String subjectHash, RateLimitProperties.Policy policy) {
    requireText(action, "action");
    requireText(subjectHash, "subjectHash");
    long now = clock.millis();
    long windowMillis = policy.window().toMillis();
    long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
    long ttlMillis = windowStart + windowMillis - now + 1_000L;
    String key = keys.key("rate-limit", action + ":" + subjectHash + ":" + windowStart);

    try {
      List<?> reply = redis.execute(SCRIPT, List.of(key), Long.toString(ttlMillis));
      if (reply == null || reply.size() != 2) {
        throw new RateLimitStoreUnavailableException();
      }
      long count = number(reply.get(0));
      long remainingMillis = number(reply.get(1));
      if (count <= 0 || remainingMillis <= 0) {
        throw new RateLimitStoreUnavailableException();
      }
      long retryAfterSeconds =
          Math.max(
              1L,
              remainingMillis / 1_000L + (remainingMillis % 1_000L == 0 ? 0 : 1));
      return new RateLimitDecision(count <= policy.limit(), retryAfterSeconds);
    } catch (RateLimitStoreUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new RateLimitStoreUnavailableException(exception);
    }
  }

  private static long number(Object value) {
    if (!(value instanceof Number number)) {
      throw new RateLimitStoreUnavailableException();
    }
    return number.longValue();
  }

  private static DefaultRedisScript<List> script() {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/rate-limit-fixed-window.lua")));
    script.setResultType(List.class);
    return script;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
