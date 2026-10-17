package com.volunteerflow.infrastructure.ratelimit;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies endpoint-specific rate-limit policies without exposing raw subjects to Redis or logs. */
public final class RateLimitGuard {

  private static final Logger log = LoggerFactory.getLogger(RateLimitGuard.class);

  private final RateLimitProperties properties;
  private final RateLimitStore store;
  private final LocalLoginRateLimiter localLogin;
  private final RateLimitSubjectHasher hasher;

  public RateLimitGuard(
      RateLimitProperties properties,
      RateLimitStore store,
      LocalLoginRateLimiter localLogin,
      RateLimitSubjectHasher hasher) {
    this.properties = properties;
    this.store = store;
    this.localLogin = localLogin;
    this.hasher = hasher;
  }

  public void checkLogin(String clientAddress, String username) {
    if (!properties.enabled()) {
      return;
    }
    String normalizedUsername = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    String ipHash = hasher.hash("login-ip", clientAddress);
    String usernameHash = hasher.hash("login-username", normalizedUsername);
    RateLimitDecision ipDecision;
    RateLimitDecision usernameDecision;
    try {
      ipDecision = store.evaluate("login-ip", ipHash, properties.login().ip());
      usernameDecision =
          store.evaluate("login-username", usernameHash, properties.login().username());
    } catch (RateLimitStoreUnavailableException exception) {
      log.warn(
          "Rate-limit store unavailable; using bounded local login fallback; failureType={}",
          failureType(exception));
      ipDecision = localLogin.evaluate("login-ip", ipHash, properties.login().ip());
      usernameDecision =
          localLogin.evaluate("login-username", usernameHash, properties.login().username());
    }
    rejectIfNecessary(ipDecision, usernameDecision);
  }

  public void checkRegistration(long userId, long activityId) {
    checkBusiness("registration", userId + ":" + activityId, properties.registration());
  }

  public void checkPromotionResponse(long userId, long offerId) {
    checkBusiness(
        "promotion-response", userId + ":" + offerId, properties.promotionResponse());
  }

  public void checkStaticCheckin(long userId, long sessionId) {
    checkBusiness("static-checkin", userId + ":" + sessionId, properties.staticCheckin());
  }

  public void checkDynamicCheckin(long userId, long sessionId) {
    checkBusiness("dynamic-checkin", userId + ":" + sessionId, properties.dynamicCheckin());
  }

  private void checkBusiness(
      String action, String rawSubject, RateLimitProperties.Policy policy) {
    if (!properties.enabled()) {
      return;
    }
    String subjectHash = hasher.hash(action, rawSubject);
    try {
      rejectIfNecessary(store.evaluate(action, subjectHash, policy));
    } catch (RateLimitStoreUnavailableException exception) {
      log.warn(
          "Rate-limit store unavailable; allowing business action: action={}, failureType={}",
          action,
          failureType(exception));
    }
  }

  private static String failureType(RateLimitStoreUnavailableException exception) {
    Throwable failure = exception.getCause() == null ? exception : exception.getCause();
    return failure.getClass().getName();
  }

  private static void rejectIfNecessary(RateLimitDecision... decisions) {
    long retryAfterSeconds = 0;
    for (RateLimitDecision decision : decisions) {
      if (!decision.allowed()) {
        retryAfterSeconds = Math.max(retryAfterSeconds, decision.retryAfterSeconds());
      }
    }
    if (retryAfterSeconds > 0) {
      throw new RateLimitExceededException(retryAfterSeconds);
    }
  }
}
