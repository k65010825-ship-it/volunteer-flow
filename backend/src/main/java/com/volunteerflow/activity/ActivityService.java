package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.volunteerflow.infrastructure.web.PageResponse;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.event.ActivityStatusPayload;
import com.volunteerflow.infrastructure.event.DomainEventCommand;
import com.volunteerflow.infrastructure.event.DomainEventPublisher;
import com.volunteerflow.infrastructure.event.DomainEventTypes;
import com.volunteerflow.infrastructure.event.NotificationEventTemplate;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the stage-1 activity lifecycle.
 *
 * <p>Drafts may be edited directly. Publishing and cancellation are explicit state transitions and
 * every successful write is recorded in the audit log within the same transaction.
 */
@Service
@Profile("!test")
public class ActivityService {
  private final ActivityMapper activities;
  private final ActivityPositionMapper positions;
  private final ActivityQuestionMapper activityQuestions;
  private final ActivityPositionQuestionMapper positionQuestions;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final RegistrationCycleMapper registrationCycles;
  private final DomainEventPublisher events;
  private final ActivityDetailCache detailCache;
  private final ActivityDetailCacheInvalidator cacheInvalidator;
  private final Clock clock;

  public ActivityService(
      ActivityMapper activities,
      ActivityPositionMapper positions,
      ActivityQuestionMapper activityQuestions,
      ActivityPositionQuestionMapper positionQuestions,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      RegistrationCycleMapper registrationCycles,
      DomainEventPublisher events,
      ActivityDetailCache detailCache,
      ActivityDetailCacheInvalidator cacheInvalidator,
      Clock clock) {
    this.activities = activities;
    this.positions = positions;
    this.activityQuestions = activityQuestions;
    this.positionQuestions = positionQuestions;
    this.authorization = authorization;
    this.audit = audit;
    this.registrationCycles = registrationCycles;
    this.events = events;
    this.detailCache = detailCache;
    this.cacheInvalidator = cacheInvalidator;
    this.clock = clock;
  }

  @Transactional
  public Activity create(Long userId, Long orgId, ActivityRequest request) {
    authorization.requirePermission(userId, orgId, "activity:create");
    validateTimes(request);
    Activity activity = new Activity();
    activity.setId(IdWorker.getId());
    activity.setOrganizationId(orgId);
    apply(activity, request);
    activity.setStatus("DRAFT");
    activity.setCreatedBy(userId);
    activity.setVersion(0);
    activities.insert(activity);
    audit.record(orgId, userId, "activity.created", "activity", activity.getId(), null);
    return activity;
  }

  @Transactional
  public Activity update(Long userId, Long id, ActivityRequest request) {
    Activity activity = required(id);
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:create");
    if (!"DRAFT".equals(activity.getStatus()))
      throw conflict("ACTIVITY_NOT_DRAFT", "Only draft activities can be directly edited");
    validateTimes(request);
    apply(activity, request);
    activities.updateById(activity);
    audit.record(activity.getOrganizationId(), userId, "activity.updated", "activity", id, null);
    return activity;
  }

  @Transactional
  public ActivityPosition addPosition(Long userId, Long activityId, PositionRequest request) {
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requireMembership(userId, activity.getOrganizationId());
    boolean published = "PUBLISHED".equals(activity.getStatus());
    if (!published && !"DRAFT".equals(activity.getStatus())) {
      throw conflict("ACTIVITY_CHANGE_NOT_ALLOWED", "Positions cannot be added to a final activity");
    }
    authorization.requirePermission(
        userId, activity.getOrganizationId(), published ? "activity:change" : "activity:create");
    boolean oneTimeMissing = (request.serviceStartAt() == null) != (request.serviceEndAt() == null);
    if (oneTimeMissing
        || (request.serviceStartAt() != null
            && (!request.serviceStartAt().isBefore(request.serviceEndAt())
                || request.serviceStartAt().isBefore(activity.getActivityStartAt())
                || request.serviceEndAt().isAfter(activity.getActivityEndAt()))))
      throw semantic(
          "INVALID_POSITION_TIME",
          "Position service times must be a valid pair within the activity schedule");
    ActivityPosition position = new ActivityPosition();
    position.setId(IdWorker.getId());
    position.setOrganizationId(activity.getOrganizationId());
    position.setActivityId(activity.getId());
    position.setName(request.name().trim());
    position.setDescription(request.description().trim());
    position.setCapacity(request.capacity());
    position.setRegistrationMode(request.registrationMode());
    position.setPromotionTimeoutMinutes(
        request.promotionTimeoutMinutes() == null ? 120 : request.promotionTimeoutMinutes());
    position.setServiceStartAt(request.serviceStartAt());
    position.setServiceEndAt(request.serviceEndAt());
    position.setMeetingLocation(trim(request.meetingLocation()));
    position.setNextWaitlistSequence(1L);
    position.setStatus(published ? "DRAFT" : "ACTIVE");
    position.setVersion(0);
    if (positions.insert(position) != 1) {
      throw new OptimisticLockingFailureException("Position insert affected no row");
    }
    audit.record(
        activity.getOrganizationId(),
        userId,
        "activity.position.created",
        "activity_position",
        position.getId(),
        null);
    return position;
  }

  @Transactional
  public Activity publish(Long userId, Long id) {
    // Lock order for form configuration: activity -> positions -> questions.
    Activity activity = activities.selectByIdForUpdate(id);
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:publish");
    if (!"DRAFT".equals(activity.getStatus()))
      throw conflict("ACTIVITY_NOT_DRAFT", "Only draft activities can be published");
    // Publishing is allowed only after both the schedule and staffing structure are complete.
    validate(activity);
    List<ActivityPosition> activePositions =
        positions.selectActiveByActivityForUpdate(activity.getOrganizationId(), id);
    if (activePositions.isEmpty())
      throw semantic("ACTIVITY_POSITION_REQUIRED", "At least one active position is required");
    // Locking reads bypass both an older InnoDB snapshot and MyBatis session-cache entries.
    // Holding the activity lock prevents question writers from changing this form until commit.
    int commonQuestionCount = activityQuestions.selectIdsByActivityForUpdate(id).size();
    Map<Long, Long> positionQuestionCounts =
        positionQuestions.selectPositionIdsByActivityForUpdate(id).stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    if (activePositions.stream()
        .anyMatch(
            position ->
                commonQuestionCount + positionQuestionCounts.getOrDefault(position.getId(), 0L)
                    > 10)) {
      throw semantic(
          "TOO_MANY_REGISTRATION_QUESTIONS",
          "Each position may have at most ten combined registration questions");
    }
    activity.setStatus("PUBLISHED");
    activity.setPublishedAt(now());
    activities.updateById(activity);
    audit.record(activity.getOrganizationId(), userId, "activity.published", "activity", id, null);
    events.publish(new DomainEventCommand(activity.getOrganizationId(), id, "ACTIVITY",
        id.toString(), DomainEventTypes.ACTIVITY_PUBLISHED, new ActivityStatusPayload(userId, "PUBLISHED")));
    cacheInvalidator.evictAfterCommit(id);
    return activity;
  }

  @Transactional
  public Activity cancel(Long userId, Long id, String reason) {
    Activity activity = activities.selectByIdForUpdate(id);
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:cancel");
    if ("CANCELED".equals(activity.getStatus()) || "FINISHED".equals(activity.getStatus()))
      throw conflict("ACTIVITY_FINAL", "Activity is already final");
    // Capture recipients while their registration or live invitation still proves eligibility.
    LinkedHashSet<Long> affectedUserIds =
        new LinkedHashSet<>(
            registrationCycles.selectAffectedUserIdsForActivityCancellation(
                activity.getOrganizationId(), id));
    LocalDateTime canceledAt = now();
    if (activities.cancelCurrent(id, activity.getOrganizationId(), canceledAt) != 1) {
      throw new OptimisticLockingFailureException("Activity changed concurrently");
    }
    activity.setStatus("CANCELED");
    activity.setCanceledAt(canceledAt);
    if (activity.getVersion() != null) activity.setVersion(activity.getVersion() + 1);
    audit.record(activity.getOrganizationId(), userId, "activity.canceled", "activity", id, reason);
    String cancellationContent =
        activity.getTitle()
            + "已取消"
            + (reason == null || reason.isBlank() ? "" : "：" + reason.trim());
    events.publishMany(
        affectedUserIds,
        new NotificationEventTemplate(
            activity.getOrganizationId(), id, "ACTIVITY", id.toString(), DomainEventTypes.ACTIVITY_CANCELLED,
            "ACTIVITY_CANCELED",
            "活动已取消",
            cancellationContent,
            "ACTIVITY",
            id));
    cacheInvalidator.evictAfterCommit(id);
    return activity;
  }

  public List<ActivitySummary> list(Long userId, Long orgId) {
    return list(userId, orgId, 1, 20).data();
  }

  @Transactional(readOnly = true)
  public PageResponse<ActivitySummary> list(Long userId, Long orgId, int page, int size) {
    authorization.requireMembership(userId, orgId);
    long safePage = Math.max(1, page);
    long safeSize = Math.min(100, Math.max(1, size));
    var result = activities.selectPublishedPage(new Page<>(safePage, safeSize), orgId);
    List<Activity> rows = result.getRecords();
    if (rows.isEmpty()) {
      return PageResponse.of(List.of(), result.getTotal(), safePage, safeSize);
    }
    List<Long> activityIds = rows.stream().map(Activity::getId).toList();
    if (rows.stream().anyMatch(row -> !orgId.equals(row.getOrganizationId())
        || !"PUBLISHED".equals(row.getStatus()))) {
      throw new IllegalStateException("Activity page returned out-of-scope data");
    }
    List<ActivityPosition> scopedPositions = positions.selectActiveByActivities(orgId, activityIds);
    if (scopedPositions.stream().anyMatch(position -> !orgId.equals(position.getOrganizationId())
        || !activityIds.contains(position.getActivityId()) || !"ACTIVE".equals(position.getStatus()))) {
      throw new IllegalStateException("Activity position batch returned out-of-scope data");
    }
    Map<Long, List<ActivityPosition>> positionsByActivity = scopedPositions.stream()
        .collect(Collectors.groupingBy(ActivityPosition::getActivityId));
    List<ActivitySummary> items = rows.stream().map(activity -> new ActivitySummary(activity,
        positionsByActivity.getOrDefault(activity.getId(), List.of()))).toList();
    return PageResponse.of(items, result.getTotal(), safePage, safeSize);
  }

  public ActivityDetail detail(Long userId, Long id) {
    var cached = detailCache.get(id);
    if (cached.isPresent()) {
      ActivityDetail detail = cached.orElseThrow();
      authorizeDetail(userId, detail.activity());
      return detail;
    }
    Activity activity = required(id);
    authorizeDetail(userId, activity);
    ActivityDetail detail = new ActivityDetail(
        activity, positions.selectActiveByActivity(activity.getOrganizationId(), id));
    if ("PUBLISHED".equals(activity.getStatus())) {
      detailCache.put(detail);
    }
    return detail;
  }

  private void authorizeDetail(Long userId, Activity activity) {
    authorization.requireMembership(userId, activity.getOrganizationId());
    if (!"PUBLISHED".equals(activity.getStatus())) {
      authorization.requirePermission(userId, activity.getOrganizationId(), "activity:read");
    }
  }

  /**
   * Returns the protected editing representation. Draft positions never flow through the public
   * detail endpoint because their existence is visible only to activity managers.
   */
  @Transactional(readOnly = true)
  public ActivityManagementDetail managementDetail(Long userId, Long id) {
    Activity activity = required(id);
    authorization.requireMembership(userId, activity.getOrganizationId());
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:change");
    List<ManagedPosition> managedPositions =
        positions.selectManageableByActivity(activity.getOrganizationId(), id).stream()
            .map(
                position ->
                    new ManagedPosition(
                        position,
                        positionQuestions.selectByPosition(
                            activity.getOrganizationId(), id, position.getId())))
            .toList();
    return new ActivityManagementDetail(
        activity, managedPositions, activityQuestions.countByActivity(id));
  }

  private Activity required(Long id) {
    Activity activity = activities.selectById(id);
    if (activity == null)
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    return activity;
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  private void apply(Activity activity, ActivityRequest request) {
    activity.setTitle(request.title().trim());
    activity.setDescription(request.description().trim());
    activity.setLocation(request.location().trim());
    activity.setRegistrationStartAt(request.registrationStartAt());
    activity.setRegistrationEndAt(request.registrationEndAt());
    activity.setFreeCancelDeadlineAt(request.freeCancelDeadlineAt());
    activity.setActivityStartAt(request.activityStartAt());
    activity.setActivityEndAt(request.activityEndAt());
  }

  private void validateTimes(ActivityRequest request) {
    if (!request.registrationStartAt().isBefore(request.registrationEndAt())
        || !request.registrationEndAt().isBefore(request.activityStartAt())
        || !request.activityStartAt().isBefore(request.activityEndAt())
        || (request.freeCancelDeadlineAt() != null
            && request.freeCancelDeadlineAt().isAfter(request.activityStartAt())))
      throw semantic("INVALID_ACTIVITY_TIME", "Activity time ordering is invalid");
  }

  private void validate(Activity activity) {
    if (!activity.getRegistrationStartAt().isBefore(activity.getRegistrationEndAt())
        || !activity.getRegistrationEndAt().isBefore(activity.getActivityStartAt())
        || !activity.getActivityStartAt().isBefore(activity.getActivityEndAt()))
      throw semantic("INVALID_ACTIVITY_TIME", "Activity time ordering is invalid");
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  public record ActivityRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 160)
          String title,
      @jakarta.validation.constraints.NotBlank String description,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 255)
          String location,
      @jakarta.validation.constraints.NotNull LocalDateTime registrationStartAt,
      @jakarta.validation.constraints.NotNull LocalDateTime registrationEndAt,
      LocalDateTime freeCancelDeadlineAt,
      @jakarta.validation.constraints.NotNull LocalDateTime activityStartAt,
      @jakarta.validation.constraints.NotNull LocalDateTime activityEndAt) {}

  public record PositionRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
          String name,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000)
          String description,
      @jakarta.validation.constraints.Min(1) int capacity,
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "FIRST_COME|REVIEW")
          String registrationMode,
      @jakarta.validation.constraints.Min(30) @jakarta.validation.constraints.Max(720)
          Integer promotionTimeoutMinutes,
      LocalDateTime serviceStartAt,
      LocalDateTime serviceEndAt,
      @jakarta.validation.constraints.Size(max = 255) String meetingLocation) {}

  public record ActivitySummary(Activity activity, List<ActivityPosition> positions) {}

  public record ActivityDetail(Activity activity, List<ActivityPosition> positions) {}

  public record ActivityManagementDetail(
      Activity activity, List<ManagedPosition> positions, long commonQuestionCount) {}

  public record ManagedPosition(
      ActivityPosition position, List<ActivityPositionQuestion> questions) {}

  public record CancelRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
          String reason) {}
}
