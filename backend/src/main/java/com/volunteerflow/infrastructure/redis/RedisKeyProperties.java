package com.volunteerflow.infrastructure.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "volunteerflow.redis")
public record RedisKeyProperties(String environment) {

  public RedisKeyProperties {
    if (!StringUtils.hasText(environment)) {
      throw new IllegalArgumentException("volunteerflow.redis.environment must not be blank");
    }
  }
}
