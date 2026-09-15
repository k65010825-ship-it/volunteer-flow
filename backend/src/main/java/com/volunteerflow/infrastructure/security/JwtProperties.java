package com.volunteerflow.infrastructure.security;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("volunteerflow.auth.jwt")
@Data
public class JwtProperties {
  private String secret;
  private String issuer = "volunteerflow";
  private String audience = "volunteerflow-web";
  private Duration accessTokenTtl = Duration.ofMinutes(30);
}
