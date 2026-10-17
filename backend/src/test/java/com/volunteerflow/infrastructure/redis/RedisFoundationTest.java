package com.volunteerflow.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.volunteerflow.infrastructure.ratelimit.LocalLoginRateLimiter;
import com.volunteerflow.infrastructure.ratelimit.RateLimitGuard;
import com.volunteerflow.infrastructure.ratelimit.RateLimitProperties;
import com.volunteerflow.infrastructure.ratelimit.RateLimitStore;
import com.volunteerflow.infrastructure.ratelimit.RateLimitSubjectHasher;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.data.redis.host=127.0.0.1",
      "spring.data.redis.port=1",
      "volunteerflow.redis.environment=test"
    })
@ActiveProfiles("test")
class RedisFoundationTest {

  @Autowired private StringRedisTemplate stringRedisTemplate;

  @Autowired private RedisKeyFactory redisKeyFactory;

  @Autowired private Clock clock;

  @Autowired private RateLimitSubjectHasher rateLimitSubjectHasher;

  @Autowired private RateLimitStore rateLimitStore;

  @Autowired private LocalLoginRateLimiter localLoginRateLimiter;

  @Autowired private RateLimitGuard rateLimitGuard;

  @Test
  void contextStartsWithoutConnectingAndProvidesStringTemplate() {
    assertThat(stringRedisTemplate).isNotNull();
  }

  @Test
  void createsNamespacedKeysAndRejectsBlankSegments() {
    assertThat(redisKeyFactory.key("activity", "detail:123"))
        .isEqualTo("volunteerflow:test:activity:detail:123");
    assertThatThrownBy(() -> redisKeyFactory.key(" ", "123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("module");
    assertThatThrownBy(() -> redisKeyFactory.key("activity", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("businessKey");
  }

  @Test
  void providesAllRateLimitRuntimeBeansUsingTheSharedClock() {
    assertThat(clock).isNotNull();
    assertThat(rateLimitSubjectHasher).isNotNull();
    assertThat(rateLimitStore).isNotNull();
    assertThat(localLoginRateLimiter).isNotNull();
    assertThat(rateLimitGuard).isNotNull();
  }

  @Test
  void disabledModeStartsAndStillProvidesNoOpCapableGuard() {
    new ApplicationContextRunner()
        .withUserConfiguration(RedisInfrastructureConfig.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(
            StringRedisTemplate.class,
            () -> org.mockito.Mockito.mock(StringRedisTemplate.class))
        .withPropertyValues(
            "volunteerflow.redis.environment=test",
            "volunteerflow.cache.activity-detail.ttl=5m",
            "volunteerflow.cache.activity-detail.max-jitter=1m",
            "volunteerflow.checkin.dynamic-code.window=30s",
            "volunteerflow.checkin.dynamic-code.ttl=45s",
            "volunteerflow.checkin.dynamic-code.token-bytes=24",
            "volunteerflow.rate-limit.enabled=false",
            "volunteerflow.rate-limit.login.ip.limit=20",
            "volunteerflow.rate-limit.login.ip.window=1m",
            "volunteerflow.rate-limit.login.username.limit=5",
            "volunteerflow.rate-limit.login.username.window=5m",
            "volunteerflow.rate-limit.registration.limit=3",
            "volunteerflow.rate-limit.registration.window=10s",
            "volunteerflow.rate-limit.promotion-response.limit=5",
            "volunteerflow.rate-limit.promotion-response.window=1m",
            "volunteerflow.rate-limit.static-checkin.limit=5",
            "volunteerflow.rate-limit.static-checkin.window=1m",
            "volunteerflow.rate-limit.dynamic-checkin.limit=10",
            "volunteerflow.rate-limit.dynamic-checkin.window=1m",
            "volunteerflow.rate-limit.local-login.max-entries=100",
            "volunteerflow.rate-limit.local-login.cleanup-interval=1m")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RateLimitGuard.class);
              assertThat(context.getBean(RateLimitProperties.class).enabled()).isFalse();
            });
  }
}
