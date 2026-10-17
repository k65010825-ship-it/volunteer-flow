package com.volunteerflow.infrastructure.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RateLimitSubjectHasher {

  private static final String ALGORITHM = "HmacSHA256";
  private static final String DOMAIN = "volunteerflow-rate-limit-v1";

  private final byte[] secret;

  public RateLimitSubjectHasher(String keySecret) {
    this.secret = (keySecret == null ? "" : keySecret).getBytes(StandardCharsets.UTF_8);
  }

  public String hash(String purpose, String rawSubject) {
    requireText(purpose, "purpose");
    requireText(rawSubject, "rawSubject");
    if (secret.length == 0) {
      throw new IllegalStateException("Rate-limit key secret is unavailable");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      byte[] digest =
          mac.doFinal(
              (DOMAIN + '\0' + purpose + '\0' + rawSubject)
                  .getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
