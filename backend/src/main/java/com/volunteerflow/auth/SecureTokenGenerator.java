package com.volunteerflow.auth;

import java.security.SecureRandom;
import java.util.Base64;

public class SecureTokenGenerator implements RefreshTokenGenerator {
  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public String generate() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
