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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

  @Test
  void rejectsChoiceQuestionWithDuplicateOptions() {
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    QuestionRequest request =
        new QuestionRequest(
            "SINGLE_CHOICE", "可参加培训吗", true, List.of("可以", "可以"), 1);

    assertThatThrownBy(() -> service.createActivityQuestion(7L, 10L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("INVALID_QUESTION_OPTIONS");
    verify(activityQuestionMapper, never()).insert(any(ActivityQuestion.class));
  }

  @Test
  void createsChoiceQuestionWithTrimmedDistinctOptionsSerializedAsJson() {
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    QuestionRequest request =
        new QuestionRequest(
            "MULTIPLE_CHOICE", "  可参加哪些培训  ", true, List.of(" 急救 ", "礼仪"), 2);

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
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());

    ActivityQuestion result =
        service.createActivityQuestion(
            7L, 10L, new QuestionRequest("TEXT", "特长", false, List.of("不应保存"), 1));

    assertThat(result.getOptionsJson()).isNull();
  }

  @Test
  void updateRejectsPublishedActivityQuestion() {
    Activity activity = draftActivity();
    activity.setStatus("PUBLISHED");
    when(activityMapper.selectById(10L)).thenReturn(activity);

    assertThatThrownBy(
            () ->
                service.updateActivityQuestion(
                    7L,
                    10L,
                    20L,
                    new QuestionRequest("BOOLEAN", "确认参加", true, List.of(), 1)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("ACTIVITY_NOT_DRAFT");
    verify(activityQuestionMapper, never()).updateById(any(ActivityQuestion.class));
  }

  @Test
  void deletingPositionQuestionVerifiesQuestionBelongsToPositionDraft() {
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    ActivityPositionQuestion question = new ActivityPositionQuestion();
    question.setId(40L);
    question.setOrganizationId(100L);
    question.setActivityId(10L);
    question.setPositionId(31L);
    when(positionQuestionMapper.selectById(40L)).thenReturn(question);

    assertThatThrownBy(() -> service.deletePositionQuestion(7L, 30L, 40L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("QUESTION_NOT_FOUND");
    verify(positionQuestionMapper, never()).deleteById(40L);
  }

  @Test
  void createsPositionQuestionOnlyForPositionBelongingToDraftActivity() {
    when(positionMapper.selectById(30L)).thenReturn(activePosition());
    when(activityMapper.selectById(10L)).thenReturn(draftActivity());
    QuestionRequest request =
        new QuestionRequest("BOOLEAN", "是否服从调配", true, List.of("ignored"), 3);

    ActivityPositionQuestion result = service.createPositionQuestion(7L, 30L, request);

    assertThat(result.getOrganizationId()).isEqualTo(100L);
    assertThat(result.getActivityId()).isEqualTo(10L);
    assertThat(result.getPositionId()).isEqualTo(30L);
    assertThat(result.getOptionsJson()).isNull();
    verify(positionQuestionMapper).insert(result);
  }

  private Activity draftActivity() {
    Activity activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(100L);
    activity.setStatus("DRAFT");
    return activity;
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
