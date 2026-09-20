package com.volunteerflow.activity;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationWriter;
import com.volunteerflow.notification.NotificationWriter.NotificationTemplate;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.PromotionService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates a published activity change, its audit record, and affected-user notifications. */
@Service
@Profile("!test")
public class ActivityChangeService {
  private final ActivityMapper activities;
  private final ActivityPositionMapper positions;
  private final ActivityQuestionMapper activityQuestions;
  private final ActivityPositionQuestionMapper positionQuestions;
  private final RegistrationCycleMapper registrationCycles;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final NotificationWriter notifications;
  private final PromotionService promotions;
  private final Clock clock;

  public ActivityChangeService(
      ActivityMapper activities,
      ActivityPositionMapper positions,
      ActivityQuestionMapper activityQuestions,
      ActivityPositionQuestionMapper positionQuestions,
      RegistrationCycleMapper registrationCycles,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      NotificationWriter notifications,
      PromotionService promotions,
      Clock clock) {
    this.activities = activities;
    this.positions = positions;
    this.activityQuestions = activityQuestions;
    this.positionQuestions = positionQuestions;
    this.registrationCycles = registrationCycles;
    this.authorization = authorization;
    this.audit = audit;
    this.notifications = notifications;
    this.promotions = promotions;
    this.clock = clock;
  }

  @Transactional
  public ActivityChangeResult changeActivity(
      Long actorId, Long activityId, ActivityChangeRequest request) {
    // The locking read is the first activity read so a stale published snapshot cannot authorize a
    // write after cancellation or finalization wins the race.
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requirePermission(actorId, activity.getOrganizationId(), "activity:change");
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw conflict(
          "ACTIVITY_CHANGE_NOT_ALLOWED", "Only a published non-final activity can be changed");
    }
    if (request == null) {
      throw semantic("INVALID_ACTIVITY_CHANGE", "Activity change details are required");
    }

    String title = requiredText(request.title(), 160, "title");
    String description = requiredText(request.description(), null, "description");
    String location = requiredText(request.location(), 255, "location");
    String reason = requiredReason(request.reason());
    requireTimes(request);

    LocalDateTime now = now();
    LocalDateTime effectiveFreeCancelDeadlineAt =
        request.freeCancelDeadlineAtPresent()
            ? request.freeCancelDeadlineAt()
            : activity.getFreeCancelDeadlineAt();
    validateTimes(activity, request, effectiveFreeCancelDeadlineAt, now);
    LinkedHashMap<String, Object> before = new LinkedHashMap<>();
    LinkedHashMap<String, Object> after = new LinkedHashMap<>();
    captureChange("title", activity.getTitle(), title, before, after);
    captureChange("description", activity.getDescription(), description, before, after);
    captureChange("location", activity.getLocation(), location, before, after);
    captureChange(
        "registrationEndAt",
        activity.getRegistrationEndAt(),
        request.registrationEndAt(),
        before,
        after);
    if (request.freeCancelDeadlineAtPresent()) {
      captureChange(
          "freeCancelDeadlineAt",
          activity.getFreeCancelDeadlineAt(),
          request.freeCancelDeadlineAt(),
          before,
          after);
    }
    captureChange(
        "activityStartAt",
        activity.getActivityStartAt(),
        request.activityStartAt(),
        before,
        after);
    captureChange(
        "activityEndAt",
        activity.getActivityEndAt(),
        request.activityEndAt(),
        before,
        after);
    if (after.isEmpty()) {
      throw semantic("NO_EFFECTIVE_CHANGE", "No activity field has changed");
    }

    if (activities.updatePublishedFields(activityId, activity.getOrganizationId(), after) != 1) {
      throw new OptimisticLockingFailureException("Published activity changed concurrently");
    }
    apply(
        activity,
        title,
        description,
        location,
        request.registrationEndAt(),
        request.freeCancelDeadlineAtPresent(),
        effectiveFreeCancelDeadlineAt,
        request.activityStartAt(),
        request.activityEndAt());
    if (activity.getVersion() != null) {
      activity.setVersion(activity.getVersion() + 1);
    }

    Map<String, Object> beforeSnapshot =
        Collections.unmodifiableMap(new LinkedHashMap<>(before));
    Map<String, Object> afterSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(after));
    Long auditId =
        audit.recordChange(
            activity.getOrganizationId(),
            actorId,
            "activity.changed",
            "activity",
            activityId,
            beforeSnapshot,
            afterSnapshot,
            reason);
    List<Long> affectedUserIds = registrationCycles.selectAffectedUserIds(activityId, now);
    int notifiedRecipients =
        notifications.writeMany(
            affectedUserIds,
            new NotificationTemplate(
                activity.getOrganizationId(),
                "ACTIVITY_CHANGE",
                "活动信息变更",
                activity.getTitle() + "已更新：" + reason,
                "ACTIVITY",
                activityId,
                "activity-change:" + activityId + ":" + auditId));
    return new ActivityChangeResult(activity, auditId, notifiedRecipients);
  }

  @Transactional
  public PositionChangeResult changePosition(
      Long actorId, Long positionId, PositionChangeRequest request) {
    ActivityPosition snapshot = positions.selectById(positionId);
    if (snapshot == null) {
      throw positionNotFound();
    }
    Activity activity = lockPublishedActivity(actorId, snapshot);
    ActivityPosition position = lockPosition(snapshot, activity);
    if (!"ACTIVE".equals(position.getStatus())) {
      throw conflict(
          "POSITION_CHANGE_NOT_ALLOWED", "Only an active position can be formally changed");
    }
    if (request == null) {
      throw semantic("INVALID_POSITION_CHANGE", "Position change details are required");
    }

    String name = requiredText(request.name(), 128, "name");
    String description = requiredText(request.description(), 1000, "description");
    String reason = requiredPositionReason(request.reason());
    String meetingLocation = optionalText(request.meetingLocation(), 255, "meetingLocation");
    if (!Objects.equals(position.getRegistrationMode(), request.registrationMode())) {
      throw conflict("POSITION_CHANGE_NOT_ALLOWED", "Position registration mode cannot change");
    }
    if (position.getCapacity() == null || request.capacity() < position.getCapacity()) {
      throw conflict("POSITION_CHANGE_NOT_ALLOWED", "Position capacity cannot decrease");
    }
    if (request.capacity() < 1) {
      throw semantic("INVALID_POSITION_CAPACITY", "Position capacity must be positive");
    }
    if (request.promotionTimeoutMinutes() == null
        || request.promotionTimeoutMinutes() < 30
        || request.promotionTimeoutMinutes() > 720) {
      throw semantic(
          "INVALID_PROMOTION_TIMEOUT", "Promotion timeout must be between 30 and 720 minutes");
    }
    validatePositionTimes(activity, request.serviceStartAt(), request.serviceEndAt());

    LinkedHashMap<String, Object> before = new LinkedHashMap<>();
    LinkedHashMap<String, Object> after = new LinkedHashMap<>();
    captureChange("name", position.getName(), name, before, after);
    captureChange("description", position.getDescription(), description, before, after);
    captureChange("capacity", position.getCapacity(), request.capacity(), before, after);
    captureChange(
        "promotionTimeoutMinutes",
        position.getPromotionTimeoutMinutes(),
        request.promotionTimeoutMinutes(),
        before,
        after);
    captureChange(
        "serviceStartAt", position.getServiceStartAt(), request.serviceStartAt(), before, after);
    captureChange("serviceEndAt", position.getServiceEndAt(), request.serviceEndAt(), before, after);
    captureChange(
        "meetingLocation", position.getMeetingLocation(), meetingLocation, before, after);
    if (after.isEmpty()) {
      throw semantic("NO_EFFECTIVE_CHANGE", "No position field has changed");
    }

    int oldCapacity = position.getCapacity();
    if (positions.updatePublishedFields(
            positionId, activity.getOrganizationId(), activity.getId(), after)
        != 1) {
      throw new OptimisticLockingFailureException("Published position changed concurrently");
    }
    apply(
        position,
        name,
        description,
        request.capacity(),
        request.promotionTimeoutMinutes(),
        request.serviceStartAt(),
        request.serviceEndAt(),
        meetingLocation);
    if (position.getVersion() != null) {
      position.setVersion(position.getVersion() + 1);
    }

    Long auditId =
        audit.recordChange(
            activity.getOrganizationId(),
            actorId,
            "position.changed",
            "activity_position",
            positionId,
            Collections.unmodifiableMap(new LinkedHashMap<>(before)),
            Collections.unmodifiableMap(new LinkedHashMap<>(after)),
            reason);
    int notifiedRecipients =
        writePositionChangeNotifications(activity, position, auditId, reason, "岗位信息变更");
    if (request.capacity() > oldCapacity) {
      promotions.fillAvailableFirstComeSeats(position, actorId, reason);
    }
    return new PositionChangeResult(position, auditId, notifiedRecipients);
  }

  @Transactional
  public PositionPublicationResult publishPosition(Long actorId, Long positionId) {
    ActivityPosition snapshot = positions.selectById(positionId);
    if (snapshot == null) {
      throw positionNotFound();
    }
    Activity activity = lockPublishedActivity(actorId, snapshot);
    ActivityPosition position = lockPosition(snapshot, activity);
    if (!"DRAFT".equals(position.getStatus())) {
      throw conflict("POSITION_NOT_DRAFT", "Only a draft position can be published");
    }

    int commonQuestionCount = activityQuestions.selectIdsByActivityForUpdate(activity.getId()).size();
    long positionQuestionCount =
        positionQuestions.selectPositionIdsByActivityForUpdate(activity.getId()).stream()
            .filter(positionId::equals)
            .count();
    if (commonQuestionCount + positionQuestionCount > 10) {
      throw semantic(
          "TOO_MANY_REGISTRATION_QUESTIONS",
          "A position may have at most ten combined registration questions");
    }
    if (positions.activateDraft(positionId, activity.getOrganizationId(), activity.getId()) != 1) {
      throw new OptimisticLockingFailureException("Draft position changed concurrently");
    }
    position.setStatus("ACTIVE");
    if (position.getVersion() != null) {
      position.setVersion(position.getVersion() + 1);
    }
    Long auditId =
        audit.recordChange(
            activity.getOrganizationId(),
            actorId,
            "position.published",
            "activity_position",
            positionId,
            Map.of("status", "DRAFT"),
            Map.of("status", "ACTIVE"),
            null);
    int notifiedRecipients =
        writePositionChangeNotifications(activity, position, auditId, null, "新增岗位发布");
    return new PositionPublicationResult(position, auditId, notifiedRecipients);
  }

  private Activity lockPublishedActivity(Long actorId, ActivityPosition snapshot) {
    Activity activity = activities.selectByIdForUpdate(snapshot.getActivityId());
    if (activity == null
        || !activity.getId().equals(snapshot.getActivityId())
        || !activity.getOrganizationId().equals(snapshot.getOrganizationId())) {
      throw positionNotFound();
    }
    authorization.requirePermission(actorId, activity.getOrganizationId(), "activity:change");
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw conflict(
          "ACTIVITY_CHANGE_NOT_ALLOWED", "Positions can only be formally changed after publication");
    }
    return activity;
  }

  private ActivityPosition lockPosition(ActivityPosition snapshot, Activity activity) {
    ActivityPosition locked = positions.selectByIdForUpdate(snapshot.getId());
    if (locked == null
        || !activity.getId().equals(locked.getActivityId())
        || !activity.getOrganizationId().equals(locked.getOrganizationId())) {
      throw positionNotFound();
    }
    return locked;
  }

  private int writePositionChangeNotifications(
      Activity activity,
      ActivityPosition position,
      Long auditId,
      String reason,
      String title) {
    List<Long> affectedUserIds = registrationCycles.selectAffectedUserIds(activity.getId(), now());
    String content =
        reason == null || reason.isBlank()
            ? position.getName() + "已发布"
            : position.getName() + "已更新：" + reason;
    return notifications.writeMany(
        affectedUserIds,
        new NotificationTemplate(
            activity.getOrganizationId(),
            "POSITION_CHANGE",
            title,
            content,
            "POSITION",
            position.getId(),
            "position-change:" + position.getId() + ":" + auditId));
  }

  private void validatePositionTimes(
      Activity activity, LocalDateTime serviceStartAt, LocalDateTime serviceEndAt) {
    boolean oneTimeMissing = (serviceStartAt == null) != (serviceEndAt == null);
    if (oneTimeMissing
        || (serviceStartAt != null
            && (!serviceStartAt.isBefore(serviceEndAt)
                || serviceStartAt.isBefore(activity.getActivityStartAt())
                || serviceEndAt.isAfter(activity.getActivityEndAt())))) {
      throw semantic(
          "INVALID_POSITION_TIME",
          "Position service times must be a valid pair within the activity schedule");
    }
  }

  private void apply(
      ActivityPosition position,
      String name,
      String description,
      int capacity,
      Integer promotionTimeoutMinutes,
      LocalDateTime serviceStartAt,
      LocalDateTime serviceEndAt,
      String meetingLocation) {
    position.setName(name);
    position.setDescription(description);
    position.setCapacity(capacity);
    position.setPromotionTimeoutMinutes(promotionTimeoutMinutes);
    position.setServiceStartAt(serviceStartAt);
    position.setServiceEndAt(serviceEndAt);
    position.setMeetingLocation(meetingLocation);
  }

  private String requiredPositionReason(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw semantic("POSITION_CHANGE_REASON_REQUIRED", "A position change reason is required");
    }
    String normalized = value.trim();
    if (normalized.length() > 500) {
      throw semantic("POSITION_CHANGE_REASON_REQUIRED", "The position change reason is too long");
    }
    return normalized;
  }

  private String optionalText(String value, int maxLength, String field) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw semantic("INVALID_POSITION_CHANGE", field + " is too long");
    }
    return normalized;
  }

  private BusinessException positionNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Activity position was not found");
  }

  private void requireTimes(ActivityChangeRequest request) {
    if (request.registrationEndAt() == null
        || request.activityStartAt() == null
        || request.activityEndAt() == null) {
      throw semantic("INVALID_ACTIVITY_TIME", "Required activity times are missing");
    }
  }

  private void validateTimes(
      Activity activity,
      ActivityChangeRequest request,
      LocalDateTime effectiveFreeCancelDeadlineAt,
      LocalDateTime now) {
    LocalDateTime registrationStartAt = activity.getRegistrationStartAt();
    LocalDateTime registrationEndAt = request.registrationEndAt();
    LocalDateTime activityStartAt = request.activityStartAt();
    LocalDateTime activityEndAt = request.activityEndAt();
    if (registrationStartAt == null
        || !registrationStartAt.isBefore(registrationEndAt)
        || !registrationEndAt.isBefore(activityStartAt)
        || !activityStartAt.isBefore(activityEndAt)
        || (effectiveFreeCancelDeadlineAt != null
            && effectiveFreeCancelDeadlineAt.isAfter(activityStartAt))) {
      throw semantic("INVALID_ACTIVITY_TIME", "Activity time ordering is invalid");
    }
    if (registrationEndAt.isBefore(activity.getRegistrationEndAt())
        && !registrationEndAt.isAfter(now)) {
      throw semantic(
          "INVALID_ACTIVITY_TIME", "An earlier registration deadline must remain in the future");
    }
    boolean activityTimeChanged =
        !Objects.equals(activity.getActivityStartAt(), activityStartAt)
            || !Objects.equals(activity.getActivityEndAt(), activityEndAt);
    if (activityTimeChanged
        && (!now.isBefore(activity.getActivityStartAt()) || !activityStartAt.isAfter(now))) {
      throw semantic(
          "INVALID_ACTIVITY_TIME", "Activity times cannot be changed after activity start");
    }
  }

  private void captureChange(
      String field,
      Object oldValue,
      Object newValue,
      Map<String, Object> before,
      Map<String, Object> after) {
    if (!Objects.equals(oldValue, newValue)) {
      before.put(field, oldValue);
      after.put(field, newValue);
    }
  }

  private void apply(
      Activity activity,
      String title,
      String description,
      String location,
      LocalDateTime registrationEndAt,
      boolean freeCancelDeadlineAtPresent,
      LocalDateTime freeCancelDeadlineAt,
      LocalDateTime activityStartAt,
      LocalDateTime activityEndAt) {
    activity.setTitle(title);
    activity.setDescription(description);
    activity.setLocation(location);
    activity.setRegistrationEndAt(registrationEndAt);
    if (freeCancelDeadlineAtPresent) {
      activity.setFreeCancelDeadlineAt(freeCancelDeadlineAt);
    }
    activity.setActivityStartAt(activityStartAt);
    activity.setActivityEndAt(activityEndAt);
  }

  private String requiredText(String value, Integer maxLength, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw semantic("INVALID_ACTIVITY_CHANGE", field + " is required");
    }
    String normalized = value.trim();
    if (maxLength != null && normalized.length() > maxLength) {
      throw semantic("INVALID_ACTIVITY_CHANGE", field + " is too long");
    }
    return normalized;
  }

  private String requiredReason(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw semantic("ACTIVITY_CHANGE_REASON_REQUIRED", "A change reason is required");
    }
    String normalized = value.trim();
    if (normalized.length() > 500) {
      throw semantic("ACTIVITY_CHANGE_REASON_REQUIRED", "The change reason is too long");
    }
    return normalized;
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  public static final class ActivityChangeRequest {
    @NotBlank
    @Size(max = 160)
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    @Size(max = 255)
    private String location;

    @NotNull
    private LocalDateTime registrationEndAt;

    private LocalDateTime freeCancelDeadlineAt;
    private boolean freeCancelDeadlineAtPresent;

    @NotNull
    private LocalDateTime activityStartAt;

    @NotNull
    private LocalDateTime activityEndAt;

    @Size(max = 500)
    private String reason;

    public ActivityChangeRequest() {}

    public ActivityChangeRequest(
        String title,
        String description,
        String location,
        LocalDateTime registrationEndAt,
        LocalDateTime freeCancelDeadlineAt,
        LocalDateTime activityStartAt,
        LocalDateTime activityEndAt,
        String reason) {
      this(
          title,
          description,
          location,
          registrationEndAt,
          activityStartAt,
          activityEndAt,
          reason);
      this.freeCancelDeadlineAt = freeCancelDeadlineAt;
      this.freeCancelDeadlineAtPresent = true;
    }

    public ActivityChangeRequest(
        String title,
        String description,
        String location,
        LocalDateTime registrationEndAt,
        LocalDateTime activityStartAt,
        LocalDateTime activityEndAt,
        String reason) {
      this.title = title;
      this.description = description;
      this.location = location;
      this.registrationEndAt = registrationEndAt;
      this.activityStartAt = activityStartAt;
      this.activityEndAt = activityEndAt;
      this.reason = reason;
    }

    public String title() {
      return title;
    }

    public String description() {
      return description;
    }

    public String location() {
      return location;
    }

    public LocalDateTime registrationEndAt() {
      return registrationEndAt;
    }

    public LocalDateTime freeCancelDeadlineAt() {
      return freeCancelDeadlineAt;
    }

    public boolean freeCancelDeadlineAtPresent() {
      return freeCancelDeadlineAtPresent;
    }

    public LocalDateTime activityStartAt() {
      return activityStartAt;
    }

    public LocalDateTime activityEndAt() {
      return activityEndAt;
    }

    public String reason() {
      return reason;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public void setLocation(String location) {
      this.location = location;
    }

    public void setRegistrationEndAt(LocalDateTime registrationEndAt) {
      this.registrationEndAt = registrationEndAt;
    }

    @JsonSetter("freeCancelDeadlineAt")
    public void setFreeCancelDeadlineAt(LocalDateTime freeCancelDeadlineAt) {
      this.freeCancelDeadlineAt = freeCancelDeadlineAt;
      this.freeCancelDeadlineAtPresent = true;
    }

    public void setActivityStartAt(LocalDateTime activityStartAt) {
      this.activityStartAt = activityStartAt;
    }

    public void setActivityEndAt(LocalDateTime activityEndAt) {
      this.activityEndAt = activityEndAt;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }

    /** Published changes reject silently ignored fields such as registrationStartAt. */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
      throw new IllegalArgumentException("Unsupported activity change field: " + field);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ActivityChangeRequest that)) {
        return false;
      }
      return freeCancelDeadlineAtPresent == that.freeCancelDeadlineAtPresent
          && Objects.equals(title, that.title)
          && Objects.equals(description, that.description)
          && Objects.equals(location, that.location)
          && Objects.equals(registrationEndAt, that.registrationEndAt)
          && Objects.equals(freeCancelDeadlineAt, that.freeCancelDeadlineAt)
          && Objects.equals(activityStartAt, that.activityStartAt)
          && Objects.equals(activityEndAt, that.activityEndAt)
          && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          title,
          description,
          location,
          registrationEndAt,
          freeCancelDeadlineAt,
          freeCancelDeadlineAtPresent,
          activityStartAt,
          activityEndAt,
          reason);
    }
  }

  public record ActivityChangeResult(Activity activity, Long auditId, int notifiedRecipients) {}

  public record PositionChangeRequest(
      @NotBlank @Size(max = 128) String name,
      @NotBlank @Size(max = 1000) String description,
      @Min(1) int capacity,
      @NotBlank @Pattern(regexp = "FIRST_COME|REVIEW") String registrationMode,
      @NotNull @Min(30) @Max(720) Integer promotionTimeoutMinutes,
      LocalDateTime serviceStartAt,
      LocalDateTime serviceEndAt,
      @Size(max = 255) String meetingLocation,
      @NotBlank @Size(max = 500) String reason) {}

  public record PositionChangeResult(
      ActivityPosition position, Long auditId, int notifiedRecipients) {}

  public record PositionPublicationResult(
      ActivityPosition position, Long auditId, int notifiedRecipients) {}
}
