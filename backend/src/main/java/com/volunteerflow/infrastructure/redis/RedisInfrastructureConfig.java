package com.volunteerflow.infrastructure.redis;

import com.volunteerflow.activity.ActivityDetailCacheProperties;
import com.volunteerflow.checkin.DynamicCheckinProperties;
import com.volunteerflow.infrastructure.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
