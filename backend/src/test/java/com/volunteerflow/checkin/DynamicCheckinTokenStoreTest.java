package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import com.volunteerflow.infrastructure.redis.RedisKeyProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class DynamicCheckinTokenStoreTest {
  @Test
  void programmingFailureDuringVerificationIsNotReportedAsRedisOutage() {
    IllegalStateException failure = new IllegalStateException("invalid store wiring");
    when(values.get(any())).thenThrow(failure);
    assertThatThrownBy(
            () ->
                store.verify(
                    101L, 1_792_070_100L, "dG9rZW4", Instant.ofEpochSecond(1_792_070_120L)))
        .isSameAs(failure);
  }

  @Test
  void programmingFailureDuringIssueIsNotReportedAsRedisOutage() {
    IllegalStateException failure = new IllegalStateException("invalid store wiring");
    when(tokens.generate(24)).thenReturn("candidate");
    when(values.get(any())).thenThrow(failure);
    assertThatThrownBy(() -> store.issue(101L, Instant.ofEpochSecond(1_792_070_120L)))
        .isSameAs(failure);
  }

  @Test
  void programmingFailureDuringEvictionIsNotReportedAsRedisOutage() {
    IllegalStateException failure = new IllegalStateException("invalid store wiring");
    when(redis.delete(any(java.util.Collection.class))).thenThrow(failure);
    assertThatThrownBy(() -> store.evictRecent(101L, Instant.ofEpochSecond(1_792_070_120L)))
        .isSameAs(failure);
  }

  private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values =
      org.mockito.Mockito.mock(ValueOperations.class);
  private final DynamicCheckinTokenGenerator tokens =
      org.mockito.Mockito.mock(DynamicCheckinTokenGenerator.class);
  private final DynamicCheckinTokenStore store =
      new DynamicCheckinTokenStore(
          redis,
          new RedisKeyFactory(new RedisKeyProperties("test")),
          new DynamicCheckinProperties(Duration.ofSeconds(30), Duration.ofSeconds(45), 24),
          tokens);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void sameWindowReturnsWinningToken() {
    Instant now = Instant.ofEpochSecond(1_792_070_109L);
    when(tokens.generate(24)).thenReturn("candidate");
    when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
    when(values.get("volunteerflow:test:dynamic-checkin:101:1792070100:v1")).thenReturn("winner");

    var issued = store.issue(101L, now);

    assertThat(issued.token()).isEqualTo("winner");
    assertThat(issued.windowEpochSecond()).isEqualTo(1_792_070_100L);
  }

  @Test
  void lateIssueUsesOnlyRemainingAbsoluteTtl() {
    Instant now = Instant.ofEpochSecond(1_792_070_129L);
    when(tokens.generate(24)).thenReturn("candidate");
    when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
    when(values.get(any())).thenReturn("candidate");

    var issued = store.issue(101L, now);

    verify(values)
        .setIfAbsent(
            eq("volunteerflow:test:dynamic-checkin:101:1792070100:v1"),
            eq("candidate"),
            eq(Duration.ofSeconds(16)));
    assertThat(issued.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_792_070_145L));
  }

  @Test
  void nextWindowRotatesToken() {
    when(tokens.generate(24)).thenReturn("first", "second");
    when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
    when(values.get(any()))
        .thenAnswer(
            invocation ->
                invocation.getArgument(0).toString().contains("0130") ? "second" : "first");
    assertThat(store.issue(101L, Instant.ofEpochSecond(1_792_070_100L)).windowEpochSecond())
        .isEqualTo(1_792_070_100L);
    assertThat(store.issue(101L, Instant.ofEpochSecond(1_792_070_130L)).windowEpochSecond())
        .isEqualTo(1_792_070_130L);
  }

  @Test
  void previousWindowWorksOnlyUntilAbsoluteExpiry() {
    when(values.get("volunteerflow:test:dynamic-checkin:101:1792070100:v1")).thenReturn("dG9rZW4");
    var proof =
        store.verify(101L, 1_792_070_100L, "dG9rZW4", Instant.ofEpochSecond(1_792_070_144L));
    assertThat(proof.sessionId()).isEqualTo(101L);
    assertThat(proof.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_792_070_145L));
    assertThatThrownBy(
            () ->
                store.verify(
                    101L, 1_792_070_100L, "dG9rZW4", Instant.ofEpochSecond(1_792_070_145L)))
        .isInstanceOf(DynamicCheckinTokenStore.InvalidCredentialException.class);
  }

  @Test
  void rejectsWrongAndCrossSessionTokens() {
    when(values.get("volunteerflow:test:dynamic-checkin:101:1792070100:v1")).thenReturn("dG9rZW4");
    assertThatThrownBy(
            () ->
                store.verify(
                    101L, 1_792_070_100L, "d3Jvbmc", Instant.ofEpochSecond(1_792_070_120L)))
        .isInstanceOf(DynamicCheckinTokenStore.InvalidCredentialException.class);
    assertThatThrownBy(
            () ->
                store.verify(
                    102L, 1_792_070_100L, "dG9rZW4", Instant.ofEpochSecond(1_792_070_120L)))
        .isInstanceOf(DynamicCheckinTokenStore.InvalidCredentialException.class);
  }

  @Test
  void redisFailureIsReportedWithoutTokenText() {
    when(values.get(any())).thenThrow(new RedisConnectionFailureException("down"));
    assertThatThrownBy(
            () ->
                store.verify(
                    101L, 1_792_070_100L, "secret-token", Instant.ofEpochSecond(1_792_070_120L)))
        .isInstanceOf(DynamicCheckinTokenStore.StoreUnavailableException.class)
        .hasMessageNotContaining("secret-token");
  }
}
