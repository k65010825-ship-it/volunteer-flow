package com.volunteerflow.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Read boundary exposing only the activity policy needed by registration workflows. */
@Service
@Profile("!test")
public class ActivityRegistrationPolicyService {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final ActivityMapper activities;
  private final ActivityPositionMapper positions;
  private final ActivityQuestionMapper activityQuestions;
  private final ActivityPositionQuestionMapper positionQuestions;
  private final OrganizationAuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public ActivityRegistrationPolicyService(
      ActivityMapper activities,
      ActivityPositionMapper positions,
      ActivityQuestionMapper activityQuestions,
      ActivityPositionQuestionMapper positionQuestions,
      OrganizationAuthorizationService authorization,
      ObjectMapper objectMapper) {
    this.activities = activities;
    this.positions = positions;
    this.activityQuestions = activityQuestions;
    this.positionQuestions = positionQuestions;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public RegistrationForm loadForm(Long userId, Long activityId, Long positionId) {
    Activity activity = publishedActivity(userId, activityId);
    ActivityPosition position = requirePosition(positions.selectById(positionId), activity);
    return new RegistrationForm(activity, position, loadQuestions(activity, position));
  }

  @Transactional
  public RegistrationPolicy lockPolicyForSubmission(Long userId, Long activityId, Long positionId) {
    Activity activity = freshlyRevalidatedPublishedActivity(userId, activityId);
    ActivityPosition position =
        requirePosition(positions.selectByIdForUpdate(positionId), activity);
    return new RegistrationPolicy(activity, position, loadQuestions(activity, position));
  }

  /** Allocates from a position already locked by the caller, in that same transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long allocateNextWaitlistSequence(ActivityPosition lockedPosition) {
    long sequence = lockedPosition.getNextWaitlistSequence();
    lockedPosition.setNextWaitlistSequence(Math.addExact(sequence, 1L));
    positions.updateById(lockedPosition);
    return sequence;
  }

  /** Existing registrations remain cancelable even if the activity is no longer published. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public CancellationTiming cancellationTiming(Long organizationId, Long activityId) {
    Activity activity = activities.selectByIdForSubmissionRevalidation(activityId);
    if (activity == null || !organizationId.equals(activity.getOrganizationId())) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    return new CancellationTiming(
        activity.getFreeCancelDeadlineAt(), activity.getActivityStartAt());
  }

  /** Caller must hold the owner membership lock, or already hold this position lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ActivityPosition lockPositionForRegistrationChange(Long positionId) {
    return positions.selectByIdForUpdate(positionId);
  }

  public record CancellationTiming(
      LocalDateTime freeCancelDeadlineAt, LocalDateTime activityStartAt) {}

  private Activity publishedActivity(Long userId, Long activityId) {
    Activity activity = activities.selectById(activityId);
    return requirePublishedActivity(userId, activity);
  }

  private Activity freshlyRevalidatedPublishedActivity(Long userId, Long activityId) {
    Activity activity = activities.selectByIdForSubmissionRevalidation(activityId);
    return requirePublishedActivity(userId, activity);
  }

  private Activity requirePublishedActivity(Long userId, Activity activity) {
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requireMembership(userId, activity.getOrganizationId());
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "ACTIVITY_NOT_PUBLISHED",
          "Activity is not open for registration");
    }
    return activity;
  }

  private ActivityPosition requirePosition(ActivityPosition position, Activity activity) {
    if (position == null
        || !activity.getId().equals(position.getActivityId())
        || !activity.getOrganizationId().equals(position.getOrganizationId())
        || !"ACTIVE".equals(position.getStatus())) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Activity position was not found");
    }
    return position;
  }

  private List<QuestionDefinition> loadQuestions(Activity activity, ActivityPosition position) {
    List<QuestionDefinition> questions = new ArrayList<>();
    activityQuestions.selectByActivity(activity.getOrganizationId(), activity.getId()).stream()
        .map(question -> definition("ACTIVITY", question))
        .forEach(questions::add);
    positionQuestions
        .selectByPosition(activity.getOrganizationId(), activity.getId(), position.getId())
        .stream()
        .map(question -> definition("POSITION", question))
        .forEach(questions::add);
    return List.copyOf(questions);
  }

  private QuestionDefinition definition(String scope, ActivityQuestion question) {
    return new QuestionDefinition(
        scope,
        question.getId(),
        question.getQuestionType(),
        question.getTitle(),
        Boolean.TRUE.equals(question.getRequiredQuestion()),
        deserializeOptions(question.getOptionsJson()),
        question.getSortOrder());
  }

  private QuestionDefinition definition(String scope, ActivityPositionQuestion question) {
    return new QuestionDefinition(
        scope,
        question.getId(),
        question.getQuestionType(),
        question.getTitle(),
        Boolean.TRUE.equals(question.getRequiredQuestion()),
        deserializeOptions(question.getOptionsJson()),
        question.getSortOrder());
  }

  private List<String> deserializeOptions(String optionsJson) {
    if (optionsJson == null) {
      return List.of();
    }
    try {
      return List.copyOf(objectMapper.readValue(optionsJson, STRING_LIST));
    } catch (IOException exception) {
      throw new IllegalStateException("Stored question options are invalid", exception);
    }
  }

  public record RegistrationForm(
      Activity activity, ActivityPosition position, List<QuestionDefinition> questions) {}

  public record RegistrationPolicy(
      Activity activity, ActivityPosition position, List<QuestionDefinition> questions) {}

  public record QuestionDefinition(
      String scope,
      Long id,
      String type,
      String title,
      boolean required,
      List<String> options,
      int sortOrder) {}
}
