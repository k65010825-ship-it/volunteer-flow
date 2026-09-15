package com.volunteerflow.auth;

import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "volunteerflow.bootstrap-admin")
public record AdminBootstrapProperties(
    String username, String password, String realName, String studentNumber, String contact) {
  public boolean isAbsent() {
    return values().noneMatch(StringUtils::hasText);
  }

  public boolean isComplete() {
    return values().allMatch(StringUtils::hasText);
  }

  private Stream<String> values() {
    return Stream.of(username, password, realName, studentNumber, contact);
  }
}
