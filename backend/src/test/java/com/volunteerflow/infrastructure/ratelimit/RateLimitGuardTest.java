package com.volunteerflow.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitGuardTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private final RateLimitStore store = org.mockito.Mockito.mock(RateLimitStore.class);
  private final LocalLoginRateLimiter local =
      org.mockito.Mockito.mock(LocalLoginRateLimiter.class);
  private final RateLimitSubjectHasher hasher = new RateLimitSubjectHasher(SECRET);

  @Test
  void loginChecksIpAndCanonicalUsernameInRedis() {
    RateLimitProperties properties = properties(true);
    allowStore();
    RateLimitGuard guard = guard(properties);

    guard.checkLogin("203.0.113.9", "  Alice  ");

    verify(store)
        .evaluate(
            "login-ip", hasher.hash("login-ip", "203.0.113.9"), properties.login().ip());
    verify(store)
        .evaluate(
            "login-username",
            hasher.hash("login-username", "alice"),
            properties.login().username());
    verify(local, never()).evaluate(any(), any(), any());
  }

  @Test
  void loginWhitespaceAndCaseVariantsShareSubject() {
    RateLimitProperties properties = properties(true);
    allowStore();
    RateLimitGuard guard = guard(properties);

    guard.checkLogin("203.0.113.9", " Alice ");
    guard.checkLogin("203.0.113.9", "aLiCe");

    verify(store, org.mockito.Mockito.times(2))
        .evaluate(
            "login-username",
            hasher.hash("login-username", "alice"),
            properties.login().username());
  }

  @Test
  void loginFallsBackForBothPoliciesOnAnyStoreFailure() {
    RateLimitProperties properties = properties(true);
    String ipHash = hasher.hash("login-ip", "203.0.113.9");
    String usernameHash = hasher.hash("login-username", "alice");
    when(store.evaluate("login-ip", ipHash, properties.login().ip()))
        .thenReturn(new RateLimitDecision(true, 0));
    when(store.evaluate("login-username", usernameHash, properties.login().username()))
        .thenThrow(new RateLimitStoreUnavailableException());
    when(local.evaluate(any(), any(), any())).thenReturn(new RateLimitDecision(true, 0));

    guard(properties).checkLogin("203.0.113.9", "Alice");

    verify(local).evaluate("login-ip", ipHash, properties.login().ip());
    verify(local).evaluate("login-username", usernameHash, properties.login().username());
  }

  @Test
  void loginRejectsWhenEitherDecisionRejects() {
    RateLimitProperties properties = properties(true);
    when(store.evaluate(any(), any(), any()))
        .thenReturn(new RateLimitDecision(true, 0), new RateLimitDecision(false, 7));

    assertThatThrownBy(() -> guard(properties).checkLogin("203.0.113.9", "alice"))
        .isInstanceOf(RateLimitExceededException.class)
        .extracting("retryAfterSeconds")
        .isEqualTo(7L);
  }

  @Test
  void businessActionsUseExpectedCompositeSubjects() {
    RateLimitProperties properties = properties(true);
    allowStore();
    RateLimitGuard guard = guard(properties);

    guard.checkRegistration(11, 21);
    guard.checkPromotionResponse(12, 22);
    guard.checkStaticCheckin(13, 23);
    guard.checkDynamicCheckin(14, 24);

    verify(store)
        .evaluate(
            "registration",
            hasher.hash("registration", "11:21"),
            properties.registration());
    verify(store)
        .evaluate(
            "promotion-response",
            hasher.hash("promotion-response", "12:22"),
            properties.promotionResponse());
    verify(store)
        .evaluate(
            "static-checkin",
            hasher.hash("static-checkin", "13:23"),
            properties.staticCheckin());
    verify(store)
        .evaluate(
            "dynamic-checkin",
            hasher.hash("dynamic-checkin", "14:24"),
            properties.dynamicCheckin());
  }

  @Test
  void businessActionsFailOpenOnlyForStoreUnavailable() {
    RateLimitProperties properties = properties(true);
    when(store.evaluate(any(), any(), any()))
        .thenThrow(new RateLimitStoreUnavailableException());
    assertThatCode(() -> guard(properties).checkRegistration(11, 21)).doesNotThrowAnyException();

    org.mockito.Mockito.reset(store);
    when(store.evaluate(any(), any(), any())).thenThrow(new IllegalStateException("bug"));
    assertThatThrownBy(() -> guard(properties).checkRegistration(11, 21))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("bug");
  }

  @Test
  void disabledConfigurationBypassesAllStores() {
    RateLimitGuard guard = guard(properties(false));

    guard.checkLogin("203.0.113.9", "alice");
    guard.checkRegistration(11, 21);
    guard.checkPromotionResponse(12, 22);
    guard.checkStaticCheckin(13, 23);
    guard.checkDynamicCheckin(14, 24);

    verifyNoInteractions(store, local);
  }

  @Test
  void warningTextDoesNotContainRawSubjects(CapturedOutput output) {
    RateLimitProperties properties = properties(true);
    when(store.evaluate(any(), any(), any()))
        .thenThrow(new RateLimitStoreUnavailableException());
    when(local.evaluate(any(), any(), any())).thenReturn(new RateLimitDecision(true, 0));

    guard(properties).checkLogin("203.0.113.91", "SensitiveUser");
    guard(properties).checkRegistration(981723, 874612);

    assertThat(output.getOut() + output.getErr())
        .contains("Rate-limit store unavailable")
        .doesNotContain("203.0.113.91")
        .doesNotContain("SensitiveUser")
        .doesNotContain("981723")
        .doesNotContain("874612");
  }

  private RateLimitGuard guard(RateLimitProperties properties) {
    return new RateLimitGuard(properties, store, local, hasher);
  }

  private void allowStore() {
    when(store.evaluate(any(), any(), any())).thenReturn(new RateLimitDecision(true, 0));
  }

  private RateLimitProperties properties(boolean enabled) {
    return new RateLimitProperties(
        enabled,
        SECRET,
        new RateLimitProperties.LoginPolicies(policy(5, 60), policy(4, 60)),
        policy(10, 60),
        policy(10, 60),
        policy(10, 60),
        policy(10, 60),
        new RateLimitProperties.LocalLogin(100, Duration.ofSeconds(30)));
  }

  private RateLimitProperties.Policy policy(int limit, long windowSeconds) {
    return new RateLimitProperties.Policy(limit, Duration.ofSeconds(windowSeconds));
  }
}
