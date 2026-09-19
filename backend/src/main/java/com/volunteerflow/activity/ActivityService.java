package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
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
  private final Clock clock;

  public ActivityService(
      ActivityMapper activities,
      ActivityPositionMapper positions,
      ActivityQuestionMapper activityQuestions,
      ActivityPositionQuestionMapper positionQuestions,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      Clock clock) {
    this.activities = activities;
    this.positions = positions;
    this.activityQuestions = activityQuestions;
    this.positionQuestions = positionQuestions;
    this.authorization = authorization;
    this.audit = audit;
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
    Activity activity = required(activityId);
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:create");
    if (!"DRAFT".equals(activity.getStatus()))
      throw conflict("ACTIVITY_NOT_DRAFT", "Positions can only be added directly to drafts");
    boolean oneTimeMissing = (request.serviceStartAt() == null) != (request.serviceEndAt() == null);
    if (oneTimeMissing
        || (request.serviceStartAt() != null
            && !request.serviceStartAt().isBefore(request.serviceEndAt())))
      throw semantic(
          "INVALID_POSITION_TIME", "Position service times must be a valid start/end pair");
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
    position.setStatus("ACTIVE");
    position.setVersion(0);
    positions.insert(position);
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
    return activity;
  }

  @Transactional
  public Activity cancel(Long userId, Long id, String reason) {
    Activity activity = required(id);
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:cancel");
    if ("CANCELED".equals(activity.getStatus()) || "FINISHED".equals(activity.getStatus()))
      throw conflict("ACTIVITY_FINAL", "Activity is already final");
    activity.setStatus("CANCELED");
    activity.setCanceledAt(now());
    activities.updateById(activity);
    audit.record(activity.getOrganizationId(), userId, "activity.canceled", "activity", id, reason);
    return activity;
  }

  public List<ActivitySummary> list(Long userId, Long orgId) {
    authorization.requireMembership(userId, orgId);
    return activities.selectPublished(orgId).stream()
        .map(
            activity ->
                new ActivitySummary(
                    activity, positions.selectActiveByActivity(orgId, activity.getId())))
        .toList();
  }

  public ActivityDetail detail(Long userId, Long id) {
    Activity activity = required(id);
    authorization.requireMembership(userId, activity.getOrganizationId());
    if (!"PUBLISHED".equals(activity.getStatus()))
      authorization.requirePermission(userId, activity.getOrganizationId(), "activity:read");
    return new ActivityDetail(
        activity, positions.selectActiveByActivity(activity.getOrganizationId(), id));
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

  public record CancelRequest(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500)
          String reason) {}
}
