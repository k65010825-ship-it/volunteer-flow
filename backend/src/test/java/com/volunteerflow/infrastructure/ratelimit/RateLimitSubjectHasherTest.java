package com.volunteerflow.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RateLimitSubjectHasherTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void hashIsDeterministicUrlSafeAndDoesNotExposeSubject() throws Exception {
    RateLimitSubjectHasher hasher = new RateLimitSubjectHasher(SECRET);

    String actual = hasher.hash("login-username", "Alice@example.edu");

    assertThat(actual).isEqualTo(independentHash("login-username", "Alice@example.edu"));
    assertThat(actual).matches("[A-Za-z0-9_-]{43}");
    assertThat(actual).doesNotContain("Alice", "example");
    assertThat(hasher.hash("login-username", "Alice@example.edu")).isEqualTo(actual);
  }

  @Test
  void purposeSeparatesTheSameSubject() {
    RateLimitSubjectHasher hasher = new RateLimitSubjectHasher(SECRET);

    assertThat(hasher.hash("login-ip", "192.0.2.10"))
        .isNotEqualTo(hasher.hash("login-username", "192.0.2.10"));
  }

  @Test
  void rejectsBlankPurposeOrSubject() {
    RateLimitSubjectHasher hasher = new RateLimitSubjectHasher(SECRET);

    assertThatThrownBy(() -> hasher.hash(" ", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("purpose");
    assertThatThrownBy(() -> hasher.hash("login-username", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rawSubject");
  }

  private String independentHash(String purpose, String subject) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest =
        mac.doFinal(
            ("volunteerflow-rate-limit-v1\0" + purpose + "\0" + subject)
                .getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }
}
