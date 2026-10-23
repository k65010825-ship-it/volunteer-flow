package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionDefinitionReadTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
  private final ActivityQuestionMapper common = mock(ActivityQuestionMapper.class);
  private final ActivityPositionQuestionMapper specific = mock(ActivityPositionQuestionMapper.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final RbacRoleMapper roles = mock(RbacRoleMapper.class);
  private final RbacRolePermissionMapper grants = mock(RbacRolePermissionMapper.class);
  private final ActivityQuestionService service = new ActivityQuestionService(activities, positions,
      common, specific, new OrganizationAuthorizationService(members, roles, grants), new ObjectMapper());

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "PUBLISHED", "COMPLETED"})
  void commonDefinitionsCanBeReadWithoutDraftOrFrozenWriteGates(String status) {
    fixture(status);
    when(grants.countActivePermission(100L, 30L, "activity:create")).thenReturn(1L);
    ActivityQuestion question = new ActivityQuestion();
    question.setId(70L);
    question.setOrganizationId(100L);
    question.setActivityId(10L);
    question.setTitle("Saved common question");
    when(common.selectByActivity(100L, 10L)).thenReturn(List.of(question));
    assertThat(service.activityQuestions(1L, 10L)).extracting(ActivityQuestion::getTitle)
        .containsExactly("Saved common question");
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "PUBLISHED", "COMPLETED"})
  void positionDefinitionsUseExistingCreateOrChangePermissionButNoEditStatusGate(String status) {
    fixture(status);
    when(grants.countActivePermission(100L, 30L,
        status.equals("DRAFT") ? "activity:create" : "activity:change")).thenReturn(1L);
    ActivityPositionQuestion question = new ActivityPositionQuestion();
    question.setId(80L);
    question.setOrganizationId(100L);
    question.setActivityId(10L);
    question.setPositionId(20L);
    when(specific.selectByPosition(100L, 10L, 20L)).thenReturn(List.of(question));
    assertThat(service.positionQuestions(1L, 20L)).extracting(ActivityPositionQuestion::getId)
        .containsExactly(80L);
  }

  @Test
  void nonMemberCannotReadEitherDefinition() {
    fixture("DRAFT");
    when(members.selectActiveMember(1L, 100L)).thenReturn(null);
    assertCode(() -> service.activityQuestions(1L, 10L), "ORGANIZATION_NOT_FOUND");
    assertCode(() -> service.positionQuestions(1L, 20L), "ORGANIZATION_NOT_FOUND");
  }

  @Test
  void membershipAloneDoesNotAuthorizeDefinitionReads() {
    fixture("DRAFT");
    assertCode(() -> service.activityQuestions(1L, 10L), "PERMISSION_DENIED");
    assertCode(() -> service.positionQuestions(1L, 20L), "PERMISSION_DENIED");
  }

  @Test
  void crossOrganizationPositionCannotExposeDefinitions() {
    fixture("DRAFT");
    when(grants.countActivePermission(100L, 30L, "activity:create")).thenReturn(1L);
    positions.selectById(20L).setOrganizationId(200L);
    assertCode(() -> service.positionQuestions(1L, 20L), "POSITION_NOT_FOUND");
  }

  @Test
  void missingActivityOrPositionHasNotFoundContract() {
    assertCode(() -> service.activityQuestions(1L, 10L), "ACTIVITY_NOT_FOUND");
    assertCode(() -> service.positionQuestions(1L, 20L), "POSITION_NOT_FOUND");
  }

  private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call).isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code()).isEqualTo(code);
  }

  private void fixture(String status) {
    Activity activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(100L);
    activity.setStatus(status);
    when(activities.selectById(10L)).thenReturn(activity);
    ActivityPosition position = new ActivityPosition();
    position.setId(20L);
    position.setActivityId(10L);
    position.setOrganizationId(100L);
    position.setStatus("ACTIVE");
    when(positions.selectById(20L)).thenReturn(position);
    var member = new com.volunteerflow.organization.OrganizationMember();
    member.setRoleId(30L);
    when(members.selectActiveMember(1L, 100L)).thenReturn(member);
    RbacRole role = new RbacRole();
    role.setId(30L);
    role.setOrganizationId(100L);
    role.setStatus("ACTIVE");
    when(roles.selectById(30L)).thenReturn(role);
  }
}
