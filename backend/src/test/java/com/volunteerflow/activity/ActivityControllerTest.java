package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.activity.ActivityChangeService.ActivityChangeRequest;
import com.volunteerflow.activity.ActivityChangeService.ActivityChangeResult;
import com.volunteerflow.activity.ActivityChangeService.PositionChangeRequest;
import com.volunteerflow.activity.ActivityChangeService.PositionChangeResult;
import com.volunteerflow.activity.ActivityChangeService.PositionPublicationResult;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActivityControllerTest {
  private ActivityService activities;
  private ActivityChangeService changes;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    activities = mock(ActivityService.class);
    changes = mock(ActivityChangeService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ActivityController(activities, changes))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    CurrentUser user = new CurrentUser(21L, "manager", "Manager", "20260021", "contact", "USER");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void publishedActivityChangeUsesPrincipalAndReturnsTheAuditAndRecipientCount() throws Exception {
    Activity changed = new Activity();
    changed.setId(10L);
    changed.setTitle("新标题");
    changed.setStatus("PUBLISHED");
    when(changes.changeActivity(eq(21L), eq(10L), any()))
        .thenReturn(new ActivityChangeResult(changed, 501L, 3));

    mvc.perform(
            post("/api/v1/activities/10/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.activity.title").value("新标题"))
        .andExpect(jsonPath("$.data.auditId").value(501))
        .andExpect(jsonPath("$.data.notifiedRecipients").value(3));

    verify(changes).changeActivity(21L, 10L, expectedRequest());
  }

  @ParameterizedTest
  @CsvSource({
    "403,PERMISSION_DENIED",
    "409,ACTIVITY_CHANGE_NOT_ALLOWED",
    "422,NO_EFFECTIVE_CHANGE"
  })
  void changeErrorsKeepTheirStatusAndStableCode(int statusCode, String code) throws Exception {
    when(changes.changeActivity(eq(21L), eq(10L), any()))
        .thenThrow(
            new BusinessException(
                HttpStatus.valueOf(statusCode), code, "Published activity change was rejected"));

    mvc.perform(
            post("/api/v1/activities/10/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestJson())
                .requestAttr("requestId", "activity-change-request"))
        .andExpect(status().is(statusCode))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.requestId").value("activity-change-request"));
  }

  @Test
  void prohibitedRegistrationStartFieldIsRejectedBeforeTheService() throws Exception {
    String body =
        validRequestJson().replace(
            "\"registrationEndAt\"", "\"registrationStartAt\":\"2026-09-01T09:00:00\",\"registrationEndAt\"");

    mvc.perform(
            post("/api/v1/activities/10/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(changes);
  }

  @Test
  void omittedFreeCancellationDeadlineIsDeserializedAsAbsent() throws Exception {
    Activity changed = new Activity();
    changed.setId(10L);
    when(changes.changeActivity(eq(21L), eq(10L), any()))
        .thenReturn(new ActivityChangeResult(changed, 501L, 0));

    mvc.perform(
            post("/api/v1/activities/10/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJsonWithoutFreeCancellationDeadline()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActivityChangeRequest> request =
        ArgumentCaptor.forClass(ActivityChangeRequest.class);
    verify(changes).changeActivity(eq(21L), eq(10L), request.capture());
    assertThat(request.getValue().freeCancelDeadlineAtPresent()).isFalse();
    assertThat(request.getValue().freeCancelDeadlineAt()).isNull();
  }

  @Test
  void explicitNullFreeCancellationDeadlineIsDeserializedAsPresent() throws Exception {
    Activity changed = new Activity();
    changed.setId(10L);
    when(changes.changeActivity(eq(21L), eq(10L), any()))
        .thenReturn(new ActivityChangeResult(changed, 502L, 0));

    mvc.perform(
            post("/api/v1/activities/10/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJsonWithNullFreeCancellationDeadline()))
        .andExpect(status().isOk());

    ArgumentCaptor<ActivityChangeRequest> request =
        ArgumentCaptor.forClass(ActivityChangeRequest.class);
    verify(changes).changeActivity(eq(21L), eq(10L), request.capture());
    assertThat(request.getValue().freeCancelDeadlineAtPresent()).isTrue();
    assertThat(request.getValue().freeCancelDeadlineAt()).isNull();
  }

  @Test
  void publishedPositionChangeUsesPrincipalAndReturnsChangeResult() throws Exception {
    ActivityPosition position = new ActivityPosition();
    position.setId(2L);
    position.setCapacity(21);
    when(changes.changePosition(eq(21L), eq(2L), any()))
        .thenReturn(new PositionChangeResult(position, 601L, 2));

    mvc.perform(
            post("/api/v1/positions/2/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPositionChangeJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.position.capacity").value(21))
        .andExpect(jsonPath("$.data.auditId").value(601))
        .andExpect(jsonPath("$.data.notifiedRecipients").value(2));

    verify(changes).changePosition(21L, 2L, expectedPositionChange());
  }

  @Test
  void draftPositionPublicationUsesPrincipalAndReturnsPublicationResult() throws Exception {
    ActivityPosition position = new ActivityPosition();
    position.setId(2L);
    position.setStatus("ACTIVE");
    when(changes.publishPosition(21L, 2L))
        .thenReturn(new PositionPublicationResult(position, 602L, 3));

    mvc.perform(post("/api/v1/positions/2/publication"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.position.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.auditId").value(602))
        .andExpect(jsonPath("$.data.notifiedRecipients").value(3));

    verify(changes).publishPosition(21L, 2L);
  }

  @Test
  void managementDetailReturnsForbiddenWithoutLeakingDraftPositions() throws Exception {
    when(activities.managementDetail(21L, 10L))
        .thenThrow(
            new BusinessException(
                HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Activity management is forbidden"));

    mvc.perform(get("/api/v1/activities/10/management"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
  }

  private ActivityChangeRequest expectedRequest() {
    return new ActivityChangeRequest(
        "新标题",
        "新介绍",
        "新地点",
        LocalDateTime.of(2026, 9, 26, 18, 0),
        LocalDateTime.of(2026, 9, 29, 10, 0),
        LocalDateTime.of(2026, 10, 2, 9, 0),
        LocalDateTime.of(2026, 10, 2, 13, 0),
        "场地调整");
  }

  private String validRequestJson() {
    return """
        {
          "title":"新标题",
          "description":"新介绍",
          "location":"新地点",
          "registrationEndAt":"2026-09-26T18:00:00",
          "freeCancelDeadlineAt":"2026-09-29T10:00:00",
          "activityStartAt":"2026-10-02T09:00:00",
          "activityEndAt":"2026-10-02T13:00:00",
          "reason":"场地调整"
        }
        """;
  }

  private String requestJsonWithoutFreeCancellationDeadline() {
    return """
        {
          "title":"新标题",
          "description":"新介绍",
          "location":"新地点",
          "registrationEndAt":"2026-09-26T18:00:00",
          "activityStartAt":"2026-10-02T09:00:00",
          "activityEndAt":"2026-10-02T13:00:00",
          "reason":"场地调整"
        }
        """;
  }

  private String requestJsonWithNullFreeCancellationDeadline() {
    return """
        {
          "title":"新标题",
          "description":"新介绍",
          "location":"新地点",
          "registrationEndAt":"2026-09-26T18:00:00",
          "freeCancelDeadlineAt":null,
          "activityStartAt":"2026-10-02T09:00:00",
          "activityEndAt":"2026-10-02T13:00:00",
          "reason":"场地调整"
        }
        """;
  }

  private PositionChangeRequest expectedPositionChange() {
    return new PositionChangeRequest(
        "礼仪岗",
        "负责接待",
        21,
        "FIRST_COME",
        120,
        LocalDateTime.of(2026, 10, 1, 9, 30),
        LocalDateTime.of(2026, 10, 1, 12, 30),
        "东门",
        "增加一个名额");
  }

  private String validPositionChangeJson() {
    return """
        {
          "name":"礼仪岗",
          "description":"负责接待",
          "capacity":21,
          "registrationMode":"FIRST_COME",
          "promotionTimeoutMinutes":120,
          "serviceStartAt":"2026-10-01T09:30:00",
          "serviceEndAt":"2026-10-01T12:30:00",
          "meetingLocation":"东门",
          "reason":"增加一个名额"
        }
        """;
  }
}
