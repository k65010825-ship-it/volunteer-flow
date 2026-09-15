package com.volunteerflow.infrastructure.redis;

import org.springframework.util.StringUtils;

public class RedisKeyFactory {

  private static final String APPLICATION_PREFIX = "volunteerflow";

  private final RedisKeyProperties properties;

  public RedisKeyFactory(RedisKeyProperties properties) {
    this.properties = properties;
  }

  public String key(String module, String businessKey) {
    requireText(module, "module");
    requireText(businessKey, "businessKey");
    return String.join(":", APPLICATION_PREFIX, properties.environment(), module, businessKey);
  }

  private static void requireText(String value, String segmentName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(segmentName + " must not be blank");
    }
  }
}
