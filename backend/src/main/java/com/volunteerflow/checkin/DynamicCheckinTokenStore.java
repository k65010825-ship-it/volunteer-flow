package com.volunteerflow.checkin;

import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DynamicCheckinTokenStore {
  private final StringRedisTemplate redis;
  private final RedisKeyFactory keys;
  private final DynamicCheckinProperties properties;
  private final DynamicCheckinTokenGenerator tokens;

  public DynamicCheckinTokenStore(StringRedisTemplate redis, RedisKeyFactory keys,
      DynamicCheckinProperties properties, DynamicCheckinTokenGenerator tokens) {
    this.redis = redis;
    this.keys = keys;
    this.properties = properties;
    this.tokens = tokens;
  }

  public IssuedCredential issue(long sessionId, Instant now) {
    long window = window(now);
    Instant expiresAt = Instant.ofEpochSecond(window).plus(properties.ttl());
    Duration remaining = Duration.between(now, expiresAt);
    String candidate = tokens.generate(properties.tokenBytes());
    String key = key(sessionId, window);
    try {
      redis.opsForValue().setIfAbsent(key, candidate, remaining);
      String winner = redis.opsForValue().get(key);
      if (winner == null) throw new StoreUnavailableException("Dynamic credential was not retained");
      return new IssuedCredential(window, expiresAt, winner);
    } catch (StoreUnavailableException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new StoreUnavailableException("Dynamic credential store is unavailable", exception);
    }
  }

  public VerifiedCredential verify(long sessionId, long windowEpochSecond, String token, Instant now) {
    long seconds = properties.window().toSeconds();
    Instant expiresAt = Instant.ofEpochSecond(windowEpochSecond).plus(properties.ttl());
    if (token == null || token.isBlank() || windowEpochSecond < 0
        || windowEpochSecond % seconds != 0 || windowEpochSecond > window(now)
        || !now.isBefore(expiresAt)) {
      throw new InvalidCredentialException();
    }
    try {
      String expected = redis.opsForValue().get(key(sessionId, windowEpochSecond));
      if (expected == null || !constantTimeEquals(expected, token)) throw new InvalidCredentialException();
      return new VerifiedCredential(windowEpochSecond, expiresAt);
    } catch (InvalidCredentialException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new StoreUnavailableException("Dynamic credential store is unavailable", exception);
    }
  }

  public void evictRecent(long sessionId, Instant now) {
    long current = window(now);
    try {
      redis.delete(java.util.List.of(key(sessionId, current), key(sessionId, current - properties.window().toSeconds())));
    } catch (RuntimeException exception) {
      throw new StoreUnavailableException("Dynamic credential store is unavailable", exception);
    }
  }

  private long window(Instant now) {
    long seconds = properties.window().toSeconds();
    return Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
  }

  private String key(long sessionId, long window) {
    return keys.key("dynamic-checkin", sessionId + ":" + window + ":v1");
  }

  private boolean constantTimeEquals(String expected, String actual) {
    try {
      return MessageDigest.isEqual(Base64.getUrlDecoder().decode(expected), Base64.getUrlDecoder().decode(actual));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public record IssuedCredential(long windowEpochSecond, Instant expiresAt, String token) {
    @Override public String toString() {
      return "IssuedCredential[windowEpochSecond=" + windowEpochSecond + ", expiresAt=" + expiresAt + ", token=[REDACTED]]";
    }
  }
  public record VerifiedCredential(long windowEpochSecond, Instant expiresAt) {}
  public static final class InvalidCredentialException extends RuntimeException {}
  public static final class StoreUnavailableException extends RuntimeException {
    public StoreUnavailableException(String message) { super(message); }
    public StoreUnavailableException(String message, Throwable cause) { super(message, cause); }
  }
}
