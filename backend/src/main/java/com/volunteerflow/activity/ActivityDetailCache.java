package com.volunteerflow.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.infrastructure.redis.RedisKeyFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Best-effort Cache Aside gateway for published activity details. */
@Component
public class ActivityDetailCache {

  private static final Logger log = LoggerFactory.getLogger(ActivityDetailCache.class);
  private static final int SCHEMA_VERSION = 1;
  private static final Set<String> REGISTRATION_MODES = Set.of("FIRST_COME", "REVIEW");

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final RedisKeyFactory keys;
  private final ActivityDetailCacheProperties properties;

  public ActivityDetailCache(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      RedisKeyFactory keys,
      ActivityDetailCacheProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.keys = keys;
    this.properties = properties;
  }

  public Optional<ActivityService.ActivityDetail> get(Long activityId) {
    String key = key(activityId);
    try {
      String json = redis.opsForValue().get(key);
      if (json == null) {
        return Optional.empty();
      }
      CacheDocument document = objectMapper.readValue(json, CacheDocument.class);
      validate(document, activityId);
      return Optional.of(document.toDetail());
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      log.warn("Invalid activity detail cache entry activityId={}; treating it as a miss", activityId);
      evict(activityId);
      return Optional.empty();
    } catch (RuntimeException exception) {
      log.warn(
          "Activity detail cache read failed activityId={} cause={}; falling back to MySQL",
          activityId,
          exception.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  public void put(ActivityService.ActivityDetail detail) {
    if (detail == null
        || detail.activity() == null
        || !"PUBLISHED".equals(detail.activity().getStatus())) {
      return;
    }
    Long activityId = detail.activity().getId();
    try {
      String json = objectMapper.writeValueAsString(CacheDocument.from(detail));
      redis.opsForValue().set(key(activityId), json, randomizedTtl());
    } catch (JsonProcessingException | RuntimeException exception) {
      log.warn(
          "Activity detail cache write failed activityId={} cause={}; returning database result",
          activityId,
          exception.getClass().getSimpleName());
    }
  }

  public void evict(Long activityId) {
    try {
      redis.delete(key(activityId));
    } catch (RuntimeException exception) {
      log.warn(
          "Activity detail cache eviction failed activityId={} cause={}; TTL will bound staleness",
          activityId,
          exception.getClass().getSimpleName());
    }
  }

  private String key(Long activityId) {
    if (activityId == null) {
      throw new IllegalArgumentException("activityId must not be null");
    }
    return keys.key("activity-detail", activityId + ":v1");
  }

  private Duration randomizedTtl() {
    long maxJitterMillis = properties.maxJitter().toMillis();
    long jitterMillis =
        maxJitterMillis == 0
            ? 0
            : ThreadLocalRandom.current().nextLong(maxJitterMillis + 1);
    return properties.ttl().plusMillis(jitterMillis);
  }

  private void validate(CacheDocument document, Long expectedActivityId) {
    if (document == null
        || document.schemaVersion() != SCHEMA_VERSION
        || !validActivity(document.activity(), expectedActivityId)
        || document.positions() == null
        || document.positions().stream()
            .anyMatch(position -> !validPosition(position, document.activity()))) {
      throw new IllegalArgumentException("Activity detail cache document is invalid");
    }
  }

  private boolean validActivity(CachedActivity activity, Long expectedActivityId) {
    return activity != null
        && expectedActivityId.equals(activity.id())
        && activity.organizationId() != null
        && StringUtils.hasText(activity.title())
        && StringUtils.hasText(activity.description())
        && StringUtils.hasText(activity.location())
        && activity.registrationStartAt() != null
        && activity.registrationEndAt() != null
        && activity.activityStartAt() != null
        && activity.activityEndAt() != null
        && "PUBLISHED".equals(activity.status())
        && activity.createdBy() != null
        && activity.publishedAt() != null
        && activity.version() != null;
  }

  private boolean validPosition(CachedPosition position, CachedActivity activity) {
    if (position == null
        || position.id() == null
        || !activity.id().equals(position.activityId())
        || !activity.organizationId().equals(position.organizationId())
        || !StringUtils.hasText(position.name())
        || !StringUtils.hasText(position.description())
        || position.capacity() == null
        || position.capacity() < 1
        || !REGISTRATION_MODES.contains(position.registrationMode())
        || position.promotionTimeoutMinutes() == null
        || position.promotionTimeoutMinutes() < 30
        || position.promotionTimeoutMinutes() > 720
        || position.nextWaitlistSequence() == null
        || position.nextWaitlistSequence() < 1
        || !"ACTIVE".equals(position.status())
        || position.version() == null) {
      return false;
    }
    return (position.serviceStartAt() == null) == (position.serviceEndAt() == null)
        && (position.serviceStartAt() == null
            || position.serviceStartAt().isBefore(position.serviceEndAt()));
  }

  private record CacheDocument(
      int schemaVersion, CachedActivity activity, List<CachedPosition> positions) {

    private static CacheDocument from(ActivityService.ActivityDetail detail) {
      return new CacheDocument(
          SCHEMA_VERSION,
          CachedActivity.from(detail.activity()),
          detail.positions().stream().map(CachedPosition::from).toList());
    }

    private ActivityService.ActivityDetail toDetail() {
      return new ActivityService.ActivityDetail(
          activity.toEntity(), positions.stream().map(CachedPosition::toEntity).toList());
    }
  }

  private record CachedActivity(
      Long id,
      Long organizationId,
      String title,
      String description,
      String location,
      LocalDateTime registrationStartAt,
      LocalDateTime registrationEndAt,
      LocalDateTime freeCancelDeadlineAt,
      LocalDateTime activityStartAt,
      LocalDateTime activityEndAt,
      String status,
      Long createdBy,
      LocalDateTime publishedAt,
      LocalDateTime canceledAt,
      Integer version) {

    private static CachedActivity from(Activity activity) {
      return new CachedActivity(
          activity.getId(),
          activity.getOrganizationId(),
          activity.getTitle(),
          activity.getDescription(),
          activity.getLocation(),
          activity.getRegistrationStartAt(),
          activity.getRegistrationEndAt(),
          activity.getFreeCancelDeadlineAt(),
          activity.getActivityStartAt(),
          activity.getActivityEndAt(),
          activity.getStatus(),
          activity.getCreatedBy(),
          activity.getPublishedAt(),
          activity.getCanceledAt(),
          activity.getVersion());
    }

    private Activity toEntity() {
      Activity activity = new Activity();
      activity.setId(id);
      activity.setOrganizationId(organizationId);
      activity.setTitle(title);
      activity.setDescription(description);
      activity.setLocation(location);
      activity.setRegistrationStartAt(registrationStartAt);
      activity.setRegistrationEndAt(registrationEndAt);
      activity.setFreeCancelDeadlineAt(freeCancelDeadlineAt);
      activity.setActivityStartAt(activityStartAt);
      activity.setActivityEndAt(activityEndAt);
      activity.setStatus(status);
      activity.setCreatedBy(createdBy);
      activity.setPublishedAt(publishedAt);
      activity.setCanceledAt(canceledAt);
      activity.setVersion(version);
      return activity;
    }
  }

  private record CachedPosition(
      Long id,
      Long organizationId,
      Long activityId,
      String name,
      String description,
      Integer capacity,
      String registrationMode,
      Integer promotionTimeoutMinutes,
      LocalDateTime serviceStartAt,
      LocalDateTime serviceEndAt,
      String meetingLocation,
      Long nextWaitlistSequence,
      String status,
      Integer version) {

    private static CachedPosition from(ActivityPosition position) {
      return new CachedPosition(
          position.getId(),
          position.getOrganizationId(),
          position.getActivityId(),
          position.getName(),
          position.getDescription(),
          position.getCapacity(),
          position.getRegistrationMode(),
          position.getPromotionTimeoutMinutes(),
          position.getServiceStartAt(),
          position.getServiceEndAt(),
          position.getMeetingLocation(),
          position.getNextWaitlistSequence(),
          position.getStatus(),
          position.getVersion());
    }

    private ActivityPosition toEntity() {
      ActivityPosition position = new ActivityPosition();
      position.setId(id);
      position.setOrganizationId(organizationId);
      position.setActivityId(activityId);
      position.setName(name);
      position.setDescription(description);
      position.setCapacity(capacity);
      position.setRegistrationMode(registrationMode);
      position.setPromotionTimeoutMinutes(promotionTimeoutMinutes);
      position.setServiceStartAt(serviceStartAt);
      position.setServiceEndAt(serviceEndAt);
      position.setMeetingLocation(meetingLocation);
      position.setNextWaitlistSequence(nextWaitlistSequence);
      position.setStatus(status);
      position.setVersion(version);
      return position;
    }
  }
}
