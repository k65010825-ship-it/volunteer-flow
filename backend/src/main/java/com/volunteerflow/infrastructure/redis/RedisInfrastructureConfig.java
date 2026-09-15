package com.volunteerflow.infrastructure.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisKeyProperties.class)
public class RedisInfrastructureConfig {

  @Bean
  public RedisKeyFactory redisKeyFactory(RedisKeyProperties properties) {
    return new RedisKeyFactory(properties);
  }
}
