package com.volunteerflow.infrastructure.redis;

import com.volunteerflow.activity.ActivityDetailCacheProperties;
import com.volunteerflow.checkin.DynamicCheckinProperties;
import com.volunteerflow.infrastructure.ratelimit.LocalLoginRateLimiter;
import com.volunteerflow.infrastructure.ratelimit.RateLimitGuard;
import com.volunteerflow.infrastructure.ratelimit.RateLimitProperties;
import com.volunteerflow.infrastructure.ratelimit.RateLimitStore;
import com.volunteerflow.infrastructure.ratelimit.RateLimitSubjectHasher;
import com.volunteerflow.infrastructure.ratelimit.RedisFixedWindowRateLimiter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  RedisKeyProperties.class,
  ActivityDetailCacheProperties.class,
  DynamicCheckinProperties.class,
  RateLimitProperties.class
})
public class RedisInfrastructureConfig {

  @Bean
  public RedisKeyFactory redisKeyFactory(RedisKeyProperties properties) {
    return new RedisKeyFactory(properties);
  }

  @Bean
  public RateLimitSubjectHasher rateLimitSubjectHasher(RateLimitProperties properties) {
    return new RateLimitSubjectHasher(properties.keySecret());
  }

  @Bean
  public RateLimitStore rateLimitStore(
      StringRedisTemplate redis, RedisKeyFactory keys, Clock clock) {
    return new RedisFixedWindowRateLimiter(redis, keys, clock);
  }

  @Bean
  public LocalLoginRateLimiter localLoginRateLimiter(
      Clock clock, RateLimitProperties properties) {
    return new LocalLoginRateLimiter(clock, properties.localLogin());
  }

  @Bean
  public RateLimitGuard rateLimitGuard(
      RateLimitStore store,
      LocalLoginRateLimiter localLogin,
      RateLimitSubjectHasher hasher,
      RateLimitProperties properties) {
    return new RateLimitGuard(properties, store, localLogin, hasher);
  }
}
