package com.volunteerflow.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {

  private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void acceptsDocumentedPolicies() {
    RateLimitProperties properties = documentedProperties(true, STRONG_SECRET, 10_000);

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.login().ip())
        .isEqualTo(new RateLimitProperties.Policy(20, Duration.ofMinutes(1)));
    assertThat(properties.login().username())
        .isEqualTo(new RateLimitProperties.Policy(5, Duration.ofMinutes(5)));
    assertThat(properties.registration())
        .isEqualTo(new RateLimitProperties.Policy(3, Duration.ofSeconds(10)));
    assertThat(properties.promotionResponse())
        .isEqualTo(new RateLimitProperties.Policy(5, Duration.ofMinutes(1)));
    assertThat(properties.staticCheckin())
        .isEqualTo(new RateLimitProperties.Policy(5, Duration.ofMinutes(1)));
    assertThat(properties.dynamicCheckin())
        .isEqualTo(new RateLimitProperties.Policy(10, Duration.ofMinutes(1)));
  }

  @Test
  void rejectsNonPositiveLimit() {
    assertThatThrownBy(() -> new RateLimitProperties.Policy(0, Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }

  @Test
  void rejectsWindowShorterThanOneSecond() {
    assertThatThrownBy(() -> new RateLimitProperties.Policy(1, Duration.ofMillis(999)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
  }

  @Test
  void rejectsShortKeySecretWhenEnabled() {
    assertThatThrownBy(() -> documentedProperties(true, "too-short", 10_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keySecret");
  }

  @Test
  void allowsBlankKeySecretWhenDisabled() {
    RateLimitProperties properties = documentedProperties(false, "", 10_000);

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.keySecret()).isEmpty();
  }

  @Test
  void rejectsFallbackCapacityBelowLoginLimits() {
    assertThatThrownBy(() -> documentedProperties(true, STRONG_SECRET, 19))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxEntries");
  }

  private RateLimitProperties documentedProperties(
      boolean enabled, String keySecret, int maxEntries) {
    return new RateLimitProperties(
        enabled,
        keySecret,
        new RateLimitProperties.LoginPolicies(
            new RateLimitProperties.Policy(20, Duration.ofMinutes(1)),
            new RateLimitProperties.Policy(5, Duration.ofMinutes(5))),
        new RateLimitProperties.Policy(3, Duration.ofSeconds(10)),
        new RateLimitProperties.Policy(5, Duration.ofMinutes(1)),
        new RateLimitProperties.Policy(5, Duration.ofMinutes(1)),
        new RateLimitProperties.Policy(10, Duration.ofMinutes(1)),
        new RateLimitProperties.LocalLogin(maxEntries, Duration.ofMinutes(1)));
  }
}
