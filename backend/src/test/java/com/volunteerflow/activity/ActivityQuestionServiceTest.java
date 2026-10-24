package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityQuestionService.QuestionRequest;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;

class ActivityQuestionServiceTest {
  private final ActivityMapper activityMapper = mock(ActivityMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper activityQuestionMapper = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper positionQuestionMapper =
      mock(ActivityPositionQuestionMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final ActivityQuestionService service =
      new ActivityQuestionService(
          activityMapper,
          positionMapper,
          activityQuestionMapper,
          positionQuestionMapper,
          authorization,
          new ObjectMapper());

  @BeforeEach
  void persistSingleRowQuestionWrites() {
    when(activityQuestionMapper.insert(any(ActivityQuestion.class))).thenReturn(1);
    when(activityQuestionMapper.updateDefinition(any(ActivityQuestion.class))).thenReturn(1);
    when(activityQuestionMapper.deleteById(anyLong())).thenReturn(1);
    when(positionQuestionMapper.insert(any(ActivityPositionQuestion.class))).thenReturn(1);
    when(positionQuestionMapper.updateDefinition(any(ActivityPositionQuestion.class)))
        .thenReturn(1);
    when(positionQuestionMapper.deleteById(anyLong())).thenReturn(1);
  }

  @Test
  void deletedPositionCannotExposeOrChangeRetainedQuestions() {
    ActivityPosition deleted = new ActivityPosition();
    deleted.setId(30L);
    deleted.setActivityId(10L);
    deleted.setOrganizationId(100L);
    deleted.setStatus("DELETED");
    when(positionMapper.selectById(30L)).thenReturn(deleted);
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(deleted);
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    assertThatThrownBy(() -> service.positionQuestions(7L, 30L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.createPositionQuestion(
                    7L, 30L, new QuestionRequest("TEXT", "问题", false, null, 1)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(positionQuestionMapper);
  }

  @Test
  void rejectsChoiceQuestionWithDuplicateOptions() {
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    QuestionRequest request =
        new QuestionRequest("SINGLE_CHOICE", "可参加培训吗", true, List.of("可以", "可以"), 1);

    assertThatThrownBy(() -> service.createActivityQuestion(7L, 10L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("INVALID_QUESTION_OPTIONS");
    verify(activityQuestionMapper, never()).insert(any(ActivityQuestion.class));
  }

  @Test
  void createsChoiceQuestionWithTrimmedDistinctOptionsSerializedAsJson() {
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    QuestionRequest request =
        new QuestionRequest("MULTIPLE_CHOICE", "  可参加哪些培训  ", true, List.of(" 急救 ", "礼仪"), 2);

    ActivityQuestion result = service.createActivityQuestion(7L, 10L, request);

    assertThat(result.getOrganizationId()).isEqualTo(100L);
    assertThat(result.getActivityId()).isEqualTo(10L);
    assertThat(result.getQuestionType()).isEqualTo("MULTIPLE_CHOICE");
    assertThat(result.getTitle()).isEqualTo("可参加哪些培训");
    assertThat(result.getRequiredQuestion()).isTrue();
    assertThat(result.getOptionsJson()).isEqualTo("[\"急救\",\"礼仪\"]");
    assertThat(result.getSortOrder()).isEqualTo(2);
    verify(authorization).requirePermission(7L, 100L, "activity:create");
    verify(activityQuestionMapper).insert(result);
  }

  @Test
  void textQuestionDoesNotPersistOptions() {
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());

    ActivityQuestion result =
        service.createActivityQuestion(
            7L, 10L, new QuestionRequest("TEXT", "特长", false, List.of("不应保存"), 1));

    assertThat(result.getOptionsJson()).isNull();
  }

  @Test
  void updateRejectsPublishedActivityQuestion() {
    Activity activity = draftActivity();
    activity.setStatus("PUBLISHED");
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(activity);

    assertThatThrownBy(
            () ->
                service.updateActivityQuestion(
                    7L, 10L, 20L, new QuestionRequest("BOOLEAN", "确认参加", true, List.of(), 1)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("ACTIVITY_NOT_DRAFT");
    verify(activityQuestionMapper, never()).updateById(any(ActivityQuestion.class));
  }

  @Test
  void deletingPositionQuestionVerifiesQuestionBelongsToPositionDraft() {
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    ActivityPositionQuestion question = new ActivityPositionQuestion();
    question.setId(40L);
    question.setOrganizationId(100L);
    question.setActivityId(10L);
    question.setPositionId(31L);
    when(positionQuestionMapper.selectByIdForUpdate(40L)).thenReturn(question);

    assertThatThrownBy(() -> service.deletePositionQuestion(7L, 30L, 40L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("QUESTION_NOT_FOUND");
    verify(positionQuestionMapper, never()).deleteById(40L);
  }

  @Test
  void createsPositionQuestionOnlyForPositionBelongingToDraftActivity() {
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    QuestionRequest request = new QuestionRequest("BOOLEAN", "是否服从调配", true, List.of("ignored"), 3);

    ActivityPositionQuestion result = service.createPositionQuestion(7L, 30L, request);

    assertThat(result.getOrganizationId()).isEqualTo(100L);
    assertThat(result.getActivityId()).isEqualTo(10L);
    assertThat(result.getPositionId()).isEqualTo(30L);
    assertThat(result.getOptionsJson()).isNull();
    verify(positionQuestionMapper).insert(result);
  }

  @Test
  void publishedActivityAllowsQuestionsOnlyOnItsDraftPosition() {
    Activity published = draftActivity();
    published.setStatus("PUBLISHED");
    ActivityPosition draftPosition = activePosition();
    draftPosition.setStatus("DRAFT");
    when(positionMapper.selectById(30L)).thenReturn(draftPosition);
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(published);
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(draftPosition);

    ActivityPositionQuestion result =
        service.createPositionQuestion(
            7L, 30L, new QuestionRequest("TEXT", "补充说明", false, List.of(), 1));

    assertThat(result.getPositionId()).isEqualTo(30L);
    verify(authorization).requirePermission(7L, 100L, "activity:change");
    verify(positionQuestionMapper).insert(result);
  }

  @Test
  void publishedActivityRejectsQuestionsOnAnActivePosition() {
    Activity published = draftActivity();
    published.setStatus("PUBLISHED");
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(published);
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());

    assertThatThrownBy(
            () ->
                service.createPositionQuestion(
                    7L, 30L, new QuestionRequest("TEXT", "补充说明", false, List.of(), 1)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("POSITION_NOT_DRAFT");

    verify(positionQuestionMapper, never()).insert(any(ActivityPositionQuestion.class));
  }

  @Test
  void nonMemberCannotObserveFinalActivityStateThroughPositionQuestions() {
    Activity canceled = draftActivity();
    canceled.setStatus("CANCELED");
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(canceled);
    BusinessException hidden =
        new BusinessException(
            org.springframework.http.HttpStatus.NOT_FOUND,
            "ORGANIZATION_NOT_FOUND",
            "Organization was not found");
    doThrow(hidden).when(authorization).requireMembership(99L, 100L);

    assertThatThrownBy(
            () ->
                service.createPositionQuestion(
                    99L, 30L, new QuestionRequest("TEXT", "补充说明", false, List.of(), 1)))
        .isSameAs(hidden);

    verify(authorization, never()).requirePermission(anyLong(), anyLong(), anyString());
    verify(positionMapper, never()).selectByIdForUpdate(anyLong());
    verifyNoInteractions(positionQuestionMapper);
  }

  private Activity draftActivity() {
    Activity activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(100L);
    activity.setStatus("DRAFT");
    return activity;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACTIVITY_CREATE",
        "ACTIVITY_UPDATE",
        "ACTIVITY_DELETE",
        "POSITION_CREATE",
        "POSITION_UPDATE",
        "POSITION_DELETE"
      })
  void everyMutationRevalidatesDraftAfterTakingTheSharedActivityLock(String operation) {
    Activity published = draftActivity();
    published.setStatus("PUBLISHED");
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(published);
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());

    assertThatThrownBy(() -> mutate(operation))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo(operation.startsWith("POSITION") ? "POSITION_NOT_DRAFT" : "ACTIVITY_NOT_DRAFT");
    verify(activityMapper).selectByIdForUpdate(10L);
    if (operation.startsWith("POSITION")) {
      verify(positionMapper).selectByIdForUpdate(30L);
    } else {
      verify(positionMapper, never()).selectByIdForUpdate(any());
    }
    verifyNoInteractions(activityQuestionMapper, positionQuestionMapper);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACTIVITY_CREATE",
        "ACTIVITY_UPDATE",
        "ACTIVITY_DELETE",
        "POSITION_CREATE",
        "POSITION_UPDATE",
        "POSITION_DELETE"
      })
  void everyMutationLocksActivityBeforePositionOrQuestion(String operation) {
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());
    ActivityQuestion activityQuestion = new ActivityQuestion();
    activityQuestion.setId(20L);
    activityQuestion.setOrganizationId(100L);
    activityQuestion.setActivityId(10L);
    when(activityQuestionMapper.selectByIdForUpdate(20L)).thenReturn(activityQuestion);
    ActivityPositionQuestion positionQuestion = new ActivityPositionQuestion();
    positionQuestion.setId(40L);
    positionQuestion.setOrganizationId(100L);
    positionQuestion.setActivityId(10L);
    positionQuestion.setPositionId(30L);
    when(positionQuestionMapper.selectByIdForUpdate(40L)).thenReturn(positionQuestion);

    mutate(operation);

    var order =
        inOrder(activityMapper, positionMapper, activityQuestionMapper, positionQuestionMapper);
    order.verify(activityMapper).selectByIdForUpdate(10L);
    if (operation.startsWith("POSITION")) order.verify(positionMapper).selectByIdForUpdate(30L);
    switch (operation) {
      case "ACTIVITY_CREATE" ->
          order.verify(activityQuestionMapper).insert(any(ActivityQuestion.class));
      case "ACTIVITY_UPDATE" -> {
        order.verify(activityQuestionMapper).selectByIdForUpdate(20L);
        order.verify(activityQuestionMapper).updateDefinition(activityQuestion);
        assertThat(activityQuestion.getOptionsJson()).isNull();
      }
      case "ACTIVITY_DELETE" -> {
        order.verify(activityQuestionMapper).selectByIdForUpdate(20L);
        order.verify(activityQuestionMapper).deleteById(20L);
      }
      case "POSITION_CREATE" ->
          order.verify(positionQuestionMapper).insert(any(ActivityPositionQuestion.class));
      case "POSITION_UPDATE" -> {
        order.verify(positionQuestionMapper).selectByIdForUpdate(40L);
        order.verify(positionQuestionMapper).updateDefinition(positionQuestion);
        assertThat(positionQuestion.getOptionsJson()).isNull();
      }
      case "POSITION_DELETE" -> {
        order.verify(positionQuestionMapper).selectByIdForUpdate(40L);
        order.verify(positionQuestionMapper).deleteById(40L);
      }
      default -> throw new IllegalArgumentException(operation);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ACTIVITY_CREATE",
        "ACTIVITY_UPDATE",
        "ACTIVITY_DELETE",
        "POSITION_CREATE",
        "POSITION_UPDATE",
        "POSITION_DELETE"
      })
  void zeroRowQuestionMutationNeverReturnsSuccess(String operation) {
    when(activityMapper.selectByIdForUpdate(10L)).thenReturn(draftActivity());
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(activePosition());
    ActivityQuestion activityQuestion = new ActivityQuestion();
    activityQuestion.setId(20L);
    activityQuestion.setOrganizationId(100L);
    activityQuestion.setActivityId(10L);
    when(activityQuestionMapper.selectByIdForUpdate(20L)).thenReturn(activityQuestion);
    ActivityPositionQuestion positionQuestion = new ActivityPositionQuestion();
    positionQuestion.setId(40L);
    positionQuestion.setOrganizationId(100L);
    positionQuestion.setActivityId(10L);
    positionQuestion.setPositionId(30L);
    when(positionQuestionMapper.selectByIdForUpdate(40L)).thenReturn(positionQuestion);
    switch (operation) {
      case "ACTIVITY_CREATE" ->
          when(activityQuestionMapper.insert(any(ActivityQuestion.class))).thenReturn(0);
      case "ACTIVITY_UPDATE" ->
          when(activityQuestionMapper.updateDefinition(any(ActivityQuestion.class))).thenReturn(0);
      case "ACTIVITY_DELETE" -> when(activityQuestionMapper.deleteById(20L)).thenReturn(0);
      case "POSITION_CREATE" ->
          when(positionQuestionMapper.insert(any(ActivityPositionQuestion.class))).thenReturn(0);
      case "POSITION_UPDATE" ->
          when(positionQuestionMapper.updateDefinition(any(ActivityPositionQuestion.class)))
              .thenReturn(0);
      case "POSITION_DELETE" -> when(positionQuestionMapper.deleteById(40L)).thenReturn(0);
      default -> throw new IllegalArgumentException(operation);
    }

    assertThatThrownBy(() -> mutate(operation))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  private void mutate(String operation) {
    var request = new QuestionRequest("TEXT", "Updated", false, List.of(), 1);
    switch (operation) {
      case "ACTIVITY_CREATE" -> service.createActivityQuestion(7L, 10L, request);
      case "ACTIVITY_UPDATE" -> service.updateActivityQuestion(7L, 10L, 20L, request);
      case "ACTIVITY_DELETE" -> service.deleteActivityQuestion(7L, 10L, 20L);
      case "POSITION_CREATE" -> service.createPositionQuestion(7L, 30L, request);
      case "POSITION_UPDATE" -> service.updatePositionQuestion(7L, 30L, 40L, request);
      case "POSITION_DELETE" -> service.deletePositionQuestion(7L, 30L, 40L);
      default -> throw new IllegalArgumentException(operation);
    }
  }

  private ActivityPosition activePosition() {
    ActivityPosition position = new ActivityPosition();
    position.setId(30L);
    position.setOrganizationId(100L);
    position.setActivityId(10L);
    position.setStatus("ACTIVE");
    return position;
  }
}
