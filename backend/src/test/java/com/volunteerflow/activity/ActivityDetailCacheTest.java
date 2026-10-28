package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import com.volunteerflow.infrastructure.redis.RedisKeyProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class ActivityDetailCacheTest {

  private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values =
      org.mockito.Mockito.mock(ValueOperations.class);
  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final ActivityDetailCacheProperties properties =
      new ActivityDetailCacheProperties(Duration.ofMinutes(5), Duration.ofSeconds(60));
  private final ActivityDetailCache cache =
      new ActivityDetailCache(
          redis, objectMapper, new RedisKeyFactory(new RedisKeyProperties("test")), properties);

  @BeforeEach
  void exposeStringValueOperations() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void publishedDetailRoundTripsThroughVersionedJsonWithBoundedTtl() {
    ActivityService.ActivityDetail original = publishedDetail();
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);

    cache.put(original);

    verify(values)
        .set(
            org.mockito.ArgumentMatchers.eq("volunteerflow:test:activity-detail:10:v1"),
            json.capture(),
            ttl.capture());
    assertThat(json.getValue()).contains("\"schemaVersion\":1");
    assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(5), Duration.ofMinutes(6));

    when(values.get("volunteerflow:test:activity-detail:10:v1")).thenReturn(json.getValue());

    ActivityService.ActivityDetail restored = cache.get(10L).orElseThrow();

    assertThat(restored.activity().getId()).isEqualTo(10L);
    assertThat(restored.activity().getOrganizationId()).isEqualTo(20L);
    assertThat(restored.activity().getTitle()).isEqualTo("新生开学典礼志愿者");
    assertThat(restored.activity().getRegistrationStartAt())
        .isEqualTo(LocalDateTime.of(2026, 8, 1, 8, 0));
    assertThat(restored.positions())
        .singleElement()
        .satisfies(
            position -> {
              assertThat(position.getId()).isEqualTo(30L);
              assertThat(position.getName()).isEqualTo("礼仪");
              assertThat(position.getRegistrationMode()).isEqualTo("FIRST_COME");
              assertThat(position.getStatus()).isEqualTo("ACTIVE");
            });
  }

  @Test
  void draftDetailIsNeverWritten() {
    ActivityService.ActivityDetail detail = publishedDetail();
    detail.activity().setStatus("DRAFT");

    cache.put(detail);

    verify(values, never()).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void corruptJsonIsTreatedAsMissAndRemoved() {
    when(values.get("volunteerflow:test:activity-detail:10:v1")).thenReturn("{not-json");

    assertThat(cache.get(10L)).isEmpty();

    verify(redis).delete("volunteerflow:test:activity-detail:10:v1");
  }

  @Test
  void unsupportedSchemaIsTreatedAsMissAndRemoved() {
    when(values.get("volunteerflow:test:activity-detail:10:v1"))
        .thenReturn("{\"schemaVersion\":2}");

    assertThat(cache.get(10L)).isEmpty();

    verify(redis).delete("volunteerflow:test:activity-detail:10:v1");
  }

  @Test
  void incompletePublishedActivityIsTreatedAsMissInsteadOfBreakingAuthorization() {
    when(values.get("volunteerflow:test:activity-detail:10:v1"))
        .thenReturn(
            "{\"schemaVersion\":1,\"activity\":{\"id\":10,\"status\":\"PUBLISHED\"},\"positions\":[]}");

    assertThat(cache.get(10L)).isEmpty();

    verify(redis).delete("volunteerflow:test:activity-detail:10:v1");
  }

  @Test
  void incompleteActivePositionIsTreatedAsMiss() throws Exception {
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    cache.put(publishedDetail());
    verify(values).set(anyString(), json.capture(), any(Duration.class));
    ObjectNode document = (ObjectNode) objectMapper.readTree(json.getValue());
    ((ObjectNode) document.withArray("positions").get(0)).putNull("name");
    when(values.get("volunteerflow:test:activity-detail:10:v1"))
        .thenReturn(objectMapper.writeValueAsString(document));

    assertThat(cache.get(10L)).isEmpty();

    verify(redis).delete("volunteerflow:test:activity-detail:10:v1");
  }

  @Test
  void redisReadFailureDegradesToCacheMiss() {
    when(values.get("volunteerflow:test:activity-detail:10:v1"))
        .thenThrow(
            new org.springframework.data.redis.RedisConnectionFailureException(
                "redis unavailable"));

    assertThat(cache.get(10L)).isEmpty();
  }

  @Test
  void redisWriteFailureDoesNotEscapeToBusinessRequest() {
    org.mockito.Mockito.doThrow(
            new org.springframework.data.redis.RedisConnectionFailureException("redis unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));

    assertThatCode(() -> cache.put(publishedDetail())).doesNotThrowAnyException();
  }

  @Test
  void redisDeleteFailureDoesNotEscapeToBusinessWrite() {
    when(redis.delete("volunteerflow:test:activity-detail:10:v1"))
        .thenThrow(
            new org.springframework.data.redis.RedisConnectionFailureException(
                "redis unavailable"));

    assertThatCode(() -> cache.evict(10L)).doesNotThrowAnyException();
  }

  @Test
  void programmingFailureDuringReadIsNotDisguisedAsCacheMiss() {
    IllegalStateException failure = new IllegalStateException("invalid cache wiring");
    when(values.get("volunteerflow:test:activity-detail:10:v1")).thenThrow(failure);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.get(10L)).isSameAs(failure);
  }

  @Test
  void programmingFailureDuringWriteIsNotSilenced() {
    IllegalStateException failure = new IllegalStateException("invalid cache wiring");
    org.mockito.Mockito.doThrow(failure)
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.put(publishedDetail()))
        .isSameAs(failure);
  }

  @Test
  void programmingFailureDuringEvictionIsNotSilenced() {
    IllegalStateException failure = new IllegalStateException("invalid cache wiring");
    when(redis.delete("volunteerflow:test:activity-detail:10:v1")).thenThrow(failure);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.evict(10L)).isSameAs(failure);
  }

  private ActivityService.ActivityDetail publishedDetail() {
    Activity activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(20L);
    activity.setTitle("新生开学典礼志愿者");
    activity.setDescription("服务新生开学典礼");
    activity.setLocation("学校礼堂");
    activity.setRegistrationStartAt(LocalDateTime.of(2026, 8, 1, 8, 0));
    activity.setRegistrationEndAt(LocalDateTime.of(2026, 8, 10, 18, 0));
    activity.setFreeCancelDeadlineAt(LocalDateTime.of(2026, 8, 20, 18, 0));
    activity.setActivityStartAt(LocalDateTime.of(2026, 8, 25, 8, 0));
    activity.setActivityEndAt(LocalDateTime.of(2026, 8, 25, 18, 0));
    activity.setStatus("PUBLISHED");
    activity.setCreatedBy(40L);
    activity.setPublishedAt(LocalDateTime.of(2026, 8, 1, 7, 0));
    activity.setVersion(3);

    ActivityPosition position = new ActivityPosition();
    position.setId(30L);
    position.setOrganizationId(20L);
    position.setActivityId(10L);
    position.setName("礼仪");
    position.setDescription("负责嘉宾接待");
    position.setCapacity(12);
    position.setRegistrationMode("FIRST_COME");
    position.setPromotionTimeoutMinutes(120);
    position.setServiceStartAt(LocalDateTime.of(2026, 8, 25, 8, 0));
    position.setServiceEndAt(LocalDateTime.of(2026, 8, 25, 12, 0));
    position.setMeetingLocation("礼堂东门");
    position.setNextWaitlistSequence(5L);
    position.setStatus("ACTIVE");
    position.setVersion(2);
    return new ActivityService.ActivityDetail(activity, List.of(position));
  }
}
