package com.volunteerflow.activity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manages draft-only activity and position registration questions. */
@Service
@Profile("!test")
public class ActivityQuestionService {
  private static final Set<String> QUESTION_TYPES =
      Set.of("TEXT", "SINGLE_CHOICE", "MULTIPLE_CHOICE", "BOOLEAN");
  private static final Set<String> CHOICE_TYPES = Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE");

  private final ActivityMapper activities;
  private final ActivityPositionMapper positions;
  private final ActivityQuestionMapper activityQuestions;
  private final ActivityPositionQuestionMapper positionQuestions;
  private final OrganizationAuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public ActivityQuestionService(
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

  @Transactional
  public ActivityQuestion createActivityQuestion(
      Long userId, Long activityId, QuestionRequest request) {
    Activity activity = editableActivity(userId, activityId);
    ValidQuestion question = validate(request);
    ActivityQuestion entity = new ActivityQuestion();
    entity.setId(IdWorker.getId());
    entity.setOrganizationId(activity.getOrganizationId());
    entity.setActivityId(activityId);
    apply(entity, question);
    activityQuestions.insert(entity);
    return entity;
  }

  @Transactional
  public ActivityPositionQuestion createPositionQuestion(
      Long userId, Long positionId, QuestionRequest request) {
    EditablePosition editable = editablePosition(userId, positionId);
    ValidQuestion question = validate(request);
    ActivityPositionQuestion entity = new ActivityPositionQuestion();
    entity.setId(IdWorker.getId());
    entity.setOrganizationId(editable.activity().getOrganizationId());
    entity.setActivityId(editable.activity().getId());
    entity.setPositionId(positionId);
    apply(entity, question);
    positionQuestions.insert(entity);
    return entity;
  }

  @Transactional
  public ActivityQuestion updateActivityQuestion(
      Long userId, Long activityId, Long questionId, QuestionRequest request) {
    Activity activity = editableActivity(userId, activityId);
    ActivityQuestion entity = activityQuestions.selectByIdForUpdate(questionId);
    if (entity == null
        || !activityId.equals(entity.getActivityId())
        || !activity.getOrganizationId().equals(entity.getOrganizationId())) {
      throw questionNotFound();
    }
    apply(entity, validate(request));
    activityQuestions.updateDefinition(entity);
    return entity;
  }

  @Transactional
  public void deleteActivityQuestion(Long userId, Long activityId, Long questionId) {
    Activity activity = editableActivity(userId, activityId);
    ActivityQuestion entity = activityQuestions.selectByIdForUpdate(questionId);
    if (entity == null
        || !activityId.equals(entity.getActivityId())
        || !activity.getOrganizationId().equals(entity.getOrganizationId())) {
      throw questionNotFound();
    }
    activityQuestions.deleteById(questionId);
  }

  @Transactional
  public ActivityPositionQuestion updatePositionQuestion(
      Long userId, Long positionId, Long questionId, QuestionRequest request) {
    EditablePosition editable = editablePosition(userId, positionId);
    ActivityPositionQuestion entity = positionQuestions.selectByIdForUpdate(questionId);
    requirePositionQuestion(entity, editable.activity(), positionId);
    apply(entity, validate(request));
    positionQuestions.updateDefinition(entity);
    return entity;
  }

  @Transactional
  public void deletePositionQuestion(Long userId, Long positionId, Long questionId) {
    EditablePosition editable = editablePosition(userId, positionId);
    ActivityPositionQuestion entity = positionQuestions.selectByIdForUpdate(questionId);
    requirePositionQuestion(entity, editable.activity(), positionId);
    positionQuestions.deleteById(questionId);
  }

  private Activity editableActivity(Long userId, Long activityId) {
    // Publication uses this same row lock. Only the locked current state may authorize editing.
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    authorization.requirePermission(userId, activity.getOrganizationId(), "activity:create");
    if (!"DRAFT".equals(activity.getStatus())) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "ACTIVITY_NOT_DRAFT",
          "Registration questions can only be changed on draft activities");
    }
    return activity;
  }

  private EditablePosition editablePosition(Long userId, Long positionId) {
    ActivityPosition position = positions.selectById(positionId);
    if (position == null) {
      throw positionNotFound();
    }
    Activity activity = editableActivity(userId, position.getActivityId());
    // The first read only discovers activityId; lock and revalidate after the activity lock.
    position = positions.selectByIdForUpdate(positionId);
    if (position == null
        || !activity.getId().equals(position.getActivityId())
        || !activity.getOrganizationId().equals(position.getOrganizationId())) {
      throw positionNotFound();
    }
    return new EditablePosition(activity, position);
  }

  private ValidQuestion validate(QuestionRequest request) {
    if (request == null || request.type() == null || !QUESTION_TYPES.contains(request.type())) {
      throw semantic("INVALID_QUESTION_TYPE", "Question type is invalid");
    }
    String title = request.title() == null ? "" : request.title().trim();
    if (title.isEmpty() || title.length() > 255) {
      throw semantic("INVALID_QUESTION_TITLE", "Question title must contain 1 to 255 characters");
    }
    if (request.sortOrder() < 1) {
      throw semantic("INVALID_QUESTION_ORDER", "Question sort order must be positive");
    }

    List<String> options = null;
    if (CHOICE_TYPES.contains(request.type())) {
      options = normalizeChoiceOptions(request.options());
    }
    return new ValidQuestion(
        request.type(), title, request.required(), options, request.sortOrder());
  }

  private List<String> normalizeChoiceOptions(List<String> rawOptions) {
    if (rawOptions == null || rawOptions.size() < 2 || rawOptions.size() > 20) {
      throw invalidOptions();
    }
    List<String> options =
        rawOptions.stream().map(value -> value == null ? "" : value.trim()).toList();
    if (options.stream().anyMatch(String::isEmpty)
        || new HashSet<>(options).size() != options.size()) {
      throw invalidOptions();
    }
    return options;
  }

  private BusinessException invalidOptions() {
    return semantic(
        "INVALID_QUESTION_OPTIONS", "Choice questions require 2 to 20 distinct options");
  }

  private void apply(ActivityQuestion entity, ValidQuestion question) {
    entity.setQuestionType(question.type());
    entity.setTitle(question.title());
    entity.setRequiredQuestion(question.required());
    entity.setOptionsJson(serializeOptions(question.options()));
    entity.setSortOrder(question.sortOrder());
  }

  private void apply(ActivityPositionQuestion entity, ValidQuestion question) {
    entity.setQuestionType(question.type());
    entity.setTitle(question.title());
    entity.setRequiredQuestion(question.required());
    entity.setOptionsJson(serializeOptions(question.options()));
    entity.setSortOrder(question.sortOrder());
  }

  private String serializeOptions(List<String> options) {
    if (options == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(options);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Question options could not be serialized", exception);
    }
  }

  private void requirePositionQuestion(
      ActivityPositionQuestion question, Activity activity, Long positionId) {
    if (question == null
        || !positionId.equals(question.getPositionId())
        || !activity.getId().equals(question.getActivityId())
        || !activity.getOrganizationId().equals(question.getOrganizationId())) {
      throw questionNotFound();
    }
  }

  private BusinessException questionNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "Registration question was not found");
  }

  private BusinessException positionNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Activity position was not found");
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public record QuestionRequest(
      @NotBlank @Pattern(regexp = "TEXT|SINGLE_CHOICE|MULTIPLE_CHOICE|BOOLEAN") String type,
      @NotBlank @Size(max = 255) String title,
      boolean required,
      @Size(max = 20) List<String> options,
      @Min(1) int sortOrder) {}

  private record ValidQuestion(
      String type, String title, boolean required, List<String> options, int sortOrder) {}

  private record EditablePosition(Activity activity, ActivityPosition position) {}
}
