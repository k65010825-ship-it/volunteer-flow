package com.volunteerflow.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import com.volunteerflow.infrastructure.redis.RedisKeyProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings({"rawtypes", "unchecked"})
class RedisFixedWindowRateLimiterTest {

  private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
  private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(12_345));
  private final RedisFixedWindowRateLimiter limiter =
      new RedisFixedWindowRateLimiter(
          redis,
          new RedisKeyFactory(new RedisKeyProperties("test")),
          clock);

  @Test
  void usesEnvironmentAwareWindowKeyAndAllowsExactlyTheLimit() {
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenReturn(List.of(3L, 8_655L));

    RateLimitDecision decision =
        limiter.evaluate("registration", "opaque-subject", policy(3, 10));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.retryAfterSeconds()).isEqualTo(9);
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(any(RedisScript.class), keys.capture(), any());
    assertThat(keys.getValue())
        .containsExactly("volunteerflow:test:rate-limit:registration:opaque-subject:10000");
  }

  @Test
  void switchesKeyAtExactWindowBoundary() {
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenReturn(List.of(1L, 1_001L));
    limiter.evaluate("login-ip", "opaque", policy(20, 10));
    clock.set(Instant.ofEpochMilli(20_000));
    limiter.evaluate("login-ip", "opaque", policy(20, 10));

    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis, org.mockito.Mockito.times(2))
        .execute(any(RedisScript.class), keys.capture(), any());
    assertThat(keys.getAllValues())
        .containsExactly(
            List.of("volunteerflow:test:rate-limit:login-ip:opaque:10000"),
            List.of("volunteerflow:test:rate-limit:login-ip:opaque:20000"));
  }

  @Test
  void retryAfterIsNeverBelowOneSecond() {
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenReturn(List.of(4L, 1L));

    RateLimitDecision decision =
        limiter.evaluate("registration", "opaque", policy(3, 10));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  void malformedScriptReplyIsUnavailable() {
    when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

    assertThatThrownBy(() -> limiter.evaluate("registration", "opaque", policy(3, 10)))
        .isInstanceOf(RateLimitStoreUnavailableException.class)
        .hasMessage("Rate-limit store is unavailable");
  }

  @Test
  void redisConnectionFailureIsUnavailable() {
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> limiter.evaluate("registration", "opaque", policy(3, 10)))
        .isInstanceOf(RateLimitStoreUnavailableException.class)
        .hasCauseInstanceOf(RedisConnectionFailureException.class);
  }

  @Test
  void doesNotLeakActionSubjectOrKeyThroughExceptionText() {
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(
            () -> limiter.evaluate("secret-action", "secret-subject", policy(3, 10)))
        .isInstanceOf(RateLimitStoreUnavailableException.class)
        .hasMessageNotContaining("secret-action")
        .hasMessageNotContaining("secret-subject")
        .hasMessageNotContaining("volunteerflow");
  }

  private RateLimitProperties.Policy policy(int limit, long seconds) {
    return new RateLimitProperties.Policy(limit, Duration.ofSeconds(seconds));
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

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
