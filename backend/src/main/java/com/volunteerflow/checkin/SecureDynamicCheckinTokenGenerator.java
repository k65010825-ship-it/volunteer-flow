package com.volunteerflow.checkin;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class SecureDynamicCheckinTokenGenerator implements DynamicCheckinTokenGenerator {
  private final SecureRandom random = new SecureRandom();

  @Override
  public String generate(int bytes) {
    byte[] entropy = new byte[bytes];
    random.nextBytes(entropy);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }
}
