package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.QuestionDefinition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.RegistrationForm;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.RegistrationPolicy;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityRegistrationPolicyServiceTest {
  private final ActivityMapper activityMapper = mock(ActivityMapper.class);
  private final ActivityPositionMapper positionMapper = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper activityQuestionMapper = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper positionQuestionMapper =
      mock(ActivityPositionQuestionMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final ActivityRegistrationPolicyService service =
      new ActivityRegistrationPolicyService(
          activityMapper,
          positionMapper,
          activityQuestionMapper,
          positionQuestionMapper,
          authorization,
          new ObjectMapper());

  @Test
  void loadFormReturnsOrderedActivityAndPositionQuestions() {
    Activity activity = publishedActivity();
    ActivityPosition position = activePosition();
    when(activityMapper.selectById(10L)).thenReturn(activity);
    when(positionMapper.selectById(30L)).thenReturn(position);
    when(activityQuestionMapper.selectByActivity(100L, 10L))
        .thenReturn(List.of(activityQuestion()));
    when(positionQuestionMapper.selectByPosition(100L, 10L, 30L))
        .thenReturn(List.of(positionQuestion()));

    RegistrationForm form = service.loadForm(7L, 10L, 30L);

    assertThat(form.activity()).isSameAs(activity);
    assertThat(form.position()).isSameAs(position);
    assertThat(form.questions())
        .extracting(QuestionDefinition::scope, QuestionDefinition::id)
        .containsExactly(tuple("ACTIVITY", 20L), tuple("POSITION", 40L));
    assertThat(form.questions().get(0).options()).containsExactly("可以", "不可以");
    verify(authorization).requireMembership(7L, 100L);
    verify(positionMapper, never()).selectByIdForUpdate(anyLong());
  }

  @Test
  void submissionPolicyLocksAndReturnsTheValidatedPosition() {
    Activity activity = publishedActivity();
    ActivityPosition position = activePosition();
    when(activityMapper.selectById(10L)).thenReturn(activity);
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(position);
    when(activityQuestionMapper.selectByActivity(100L, 10L)).thenReturn(List.of());
    when(positionQuestionMapper.selectByPosition(100L, 10L, 30L)).thenReturn(List.of());

    RegistrationPolicy policy = service.lockPolicyForSubmission(7L, 10L, 30L);

    assertThat(policy.activity()).isSameAs(activity);
    assertThat(policy.position()).isSameAs(position);
    assertThat(policy.questions()).isEmpty();
    verify(authorization).requireMembership(7L, 100L);
    verify(positionMapper).selectByIdForUpdate(30L);
    verify(positionMapper, never()).selectById(30L);
  }

  @Test
  void rejectsRegistrationFormForUnpublishedActivity() {
    Activity activity = publishedActivity();
    activity.setStatus("DRAFT");
    when(activityMapper.selectById(10L)).thenReturn(activity);

    assertThatThrownBy(() -> service.loadForm(7L, 10L, 30L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("ACTIVITY_NOT_PUBLISHED");
    verify(authorization).requireMembership(7L, 100L);
    verifyNoInteractions(activityQuestionMapper, positionQuestionMapper);
  }

  @Test
  void rejectsSubmissionWhenLockedPositionBelongsToAnotherActivity() {
    when(activityMapper.selectById(10L)).thenReturn(publishedActivity());
    ActivityPosition position = activePosition();
    position.setActivityId(11L);
    when(positionMapper.selectByIdForUpdate(30L)).thenReturn(position);

    assertThatThrownBy(() -> service.lockPolicyForSubmission(7L, 10L, 30L))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("POSITION_NOT_FOUND");
    verifyNoInteractions(activityQuestionMapper, positionQuestionMapper);
  }

  private Activity publishedActivity() {
    Activity activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(100L);
    activity.setStatus("PUBLISHED");
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

  private ActivityQuestion activityQuestion() {
    ActivityQuestion question = new ActivityQuestion();
    question.setId(20L);
    question.setQuestionType("SINGLE_CHOICE");
    question.setTitle("可参加培训吗");
    question.setRequiredQuestion(true);
    question.setOptionsJson("[\"可以\",\"不可以\"]");
    question.setSortOrder(1);
    return question;
  }

  private ActivityPositionQuestion positionQuestion() {
    ActivityPositionQuestion question = new ActivityPositionQuestion();
    question.setId(40L);
    question.setQuestionType("TEXT");
    question.setTitle("请说明相关经验");
    question.setRequiredQuestion(false);
    question.setSortOrder(1);
    return question;
  }
}
