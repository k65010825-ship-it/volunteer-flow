package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.checkin.CheckinService.*;
import com.volunteerflow.checkin.CheckinSessionService.*;
import com.volunteerflow.checkin.CheckinViews.*;
import com.volunteerflow.infrastructure.security.SecurityConfig;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.web.JacksonConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real application security filter, Jackson and advice; mutation transactions are isolated. */
@WebMvcTest(CheckinController.class)
@Import({SecurityConfig.class, JacksonConfig.class})
class CheckinControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean CheckinSessionService sessions;
  @MockitoBean CheckinService checkins;
  @MockitoBean AttendanceQueryService queries;
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 8, 0);
  private UsernamePasswordAuthenticationToken principal;

  @BeforeEach
  void setup() {
    principal = new UsernamePasswordAuthenticationToken(
        new CurrentUser(21L, "member", "Member", "20260021", "contact", "USER"), null, List.of());
  }

  @Test
  void openUsesPrincipalReturnsCreatedEnvelopeAndOnlyOneTimeCode() throws Exception {
    when(sessions.open(21L, 11L, new OpenSessionRequest(15)))
        .thenReturn(new OpenedSession(2030987654321098765L, 11L, now, now.plusMinutes(15), "ABCD2345"));
    perform(post("/api/v1/activities/11/check-in-sessions"), "{\"durationMinutes\":15,\"actorId\":999}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sessionId").value("2030987654321098765"))
        .andExpect(jsonPath("$.data.activityId").value("11"))
        .andExpect(jsonPath("$.data.code").value("ABCD2345"))
        .andExpect(jsonPath("$.data.staticCodeHash").doesNotExist());
    verify(sessions).open(21L, 11L, new OpenSessionRequest(15));
  }

  @Test
  void omittedDurationRetainsServiceDefault() throws Exception {
    perform(post("/api/v1/activities/11/check-in-sessions"), "{}")
        .andExpect(status().isCreated());
    verify(sessions).open(21L, 11L, new OpenSessionRequest(null));
  }

  @Test
  void closureUsesPrincipalAndNeverReturnsAnyCode() throws Exception {
    when(sessions.close(21L, 101L)).thenReturn(session("CLOSED"));
    var result = perform(post("/api/v1/check-in-sessions/101/closure"), "{\"actorId\":999}")
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"))
        .andExpect(jsonPath("$.data.sessionId").value("101")).andReturn();
    noSecrets(result.getResponse().getContentAsString());
    verify(sessions).close(21L, 101L);
  }

  @Test
  void currentUsesPrincipalRestoresOwnStatusAndNeverReturnsAnyCode() throws Exception {
    when(queries.current(21L, 11L)).thenReturn(new CurrentCheckin(session("OPEN"),
        new OwnAttendance(201L, 301L, 12L, "Reception", "CHECKED_IN", 401L, now)));
    var result = perform(get("/api/v1/activities/11/check-in-sessions/current").param("userId", "999"), null)
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.sessionId").value("101"))
        .andExpect(jsonPath("$.data.attendance.attendanceStatus").value("CHECKED_IN"))
        .andExpect(jsonPath("$.data.attendance.recordId").value("401")).andReturn();
    noSecrets(result.getResponse().getContentAsString());
    verify(queries).current(21L, 11L);
  }

  @Test
  void selfBodyContainsOnlyCodeAndCannotChooseActorOrActivity() throws Exception {
    var request = new CheckinRequest(11L, "ABCD2345");
    when(queries.selfRequest(21L, 101L, "ABCD2345")).thenReturn(request);
    when(checkins.selfCheckin(21L, 101L, request)).thenReturn(result("SELF_SERVICE"));
    var response = perform(post("/api/v1/check-in-sessions/101/check-ins"),
        "{\"code\":\"ABCD2345\",\"userId\":999,\"activityId\":999}")
        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").value("21"))
        .andExpect(jsonPath("$.data.activityId").value("11"))
        .andExpect(jsonPath("$.data.method").value("SELF_SERVICE")).andReturn();
    noSecrets(response.getResponse().getContentAsString());
    verify(queries).selfRequest(21L, 101L, "ABCD2345");
    verify(checkins).selfCheckin(21L, 101L, request);
  }

  @Test
  void manualBodyNeedsOnlyRegistrationAndReasonAndCannotChooseCycleOrActor() throws Exception {
    var request = new ManualCheckinRequest(201L, 302L, "present");
    when(queries.manualRequest(21L, 101L, 201L, "present")).thenReturn(request);
    when(checkins.manualCheckin(21L, 101L, request)).thenReturn(result("MANUAL"));
    var response = perform(post("/api/v1/check-in-sessions/101/manual-check-ins"),
        "{\"registrationId\":\"201\",\"reason\":\"present\",\"registrationCycleId\":999,\"actorId\":999}")
        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.recordId").value("401"))
        .andExpect(jsonPath("$.data.method").value("MANUAL")).andReturn();
    noSecrets(response.getResponse().getContentAsString());
    verify(queries).manualRequest(21L, 101L, 201L, "present");
    verify(checkins).manualCheckin(21L, 101L, request);
  }

  @Test
  void summaryUsesPrincipalAndKeepsNumericRateWithGlobalStringLongs() throws Exception {
    when(queries.summary(21L, 11L)).thenReturn(new AttendanceSummary(11L, "FINISHED", true,
        5L, 2L, 1L, 2L, new BigDecimal("0.4"),
        List.of(new PositionAttendance(12L, "Reception", 3L, 1L, 1L, 1L, new BigDecimal("0.3333")))));
    var response = perform(get("/api/v1/activities/11/attendance-summary").param("actorId", "999"), null)
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalStatistics").value(true))
        .andExpect(jsonPath("$.data.confirmedCount").value("5"))
        .andExpect(jsonPath("$.data.checkinRate").value(0.4))
        .andExpect(jsonPath("$.data.positions[0].positionId").value("12")).andReturn();
    noSecrets(response.getResponse().getContentAsString());
    verify(queries).summary(21L, 11L);
  }

  @ParameterizedTest @ValueSource(strings = {
      "POST /api/v1/activities/11/check-in-sessions", "POST /api/v1/check-in-sessions/101/closure",
      "GET /api/v1/activities/11/check-in-sessions/current", "POST /api/v1/check-in-sessions/101/check-ins",
      "POST /api/v1/check-in-sessions/101/manual-check-ins", "GET /api/v1/activities/11/attendance-summary"})
  void allSixRoutesRequireAuthenticationAndReturn401(String route) throws Exception {
    String[] parts = route.split(" ");
    mvc.perform((parts[0].equals("GET") ? get(parts[1]) : post(parts[1]))
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(sessions, checkins, queries);
  }

  @Test
  void permissionDeniedIs403WithExistingBusinessCode() throws Exception {
    when(queries.summary(21L, 11L)).thenThrow(error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED"));
    perform(get("/api/v1/activities/11/attendance-summary"), null)
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void missingOrHiddenResourceIs404() throws Exception {
    when(queries.current(21L, 11L)).thenThrow(error(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND"));
    perform(get("/api/v1/activities/11/check-in-sessions/current"), null)
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
  }

  @Test
  void alreadyOpenBusinessConflictIs409() throws Exception {
    when(sessions.open(eq(21L), eq(11L), any())).thenThrow(error(HttpStatus.CONFLICT, "CHECKIN_SESSION_ALREADY_OPEN"));
    perform(post("/api/v1/activities/11/check-in-sessions"), "{}")
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKIN_SESSION_ALREADY_OPEN"));
  }

  @Test
  void incorrectCodeIsBusiness422AndDoesNotEchoSecret() throws Exception {
    var request = new CheckinRequest(11L, "WRONG234");
    when(queries.selfRequest(21L, 101L, "WRONG234")).thenReturn(request);
    when(checkins.selfCheckin(21L, 101L, request)).thenThrow(error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CHECKIN_CODE"));
    var response = perform(post("/api/v1/check-in-sessions/101/check-ins"), "{\"code\":\"WRONG234\"}")
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_CHECKIN_CODE"))
        .andExpect(jsonPath("$.requestId").value("checkin-request")).andReturn();
    assertThat(response.getResponse().getContentAsString()).doesNotContain("WRONG234");
  }

  @ParameterizedTest @ValueSource(strings = {"4", "121"})
  void invalidDurationIsExistingBeanValidation400(String duration) throws Exception {
    perform(post("/api/v1/activities/11/check-in-sessions"), "{\"durationMinutes\":" + duration + "}")
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(sessions);
  }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"code\":\" \"}"})
  void missingOrBlankCodeFails400BeforeService(String json) throws Exception {
    perform(post("/api/v1/check-in-sessions/101/check-ins"), json)
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(queries, checkins);
  }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"reason\":\"present\"}", "{\"registrationId\":201}", "{\"registrationId\":201,\"reason\":\" \"}"})
  void incompleteManualRequestFails400BeforeTargetLookup(String json) throws Exception {
    perform(post("/api/v1/check-in-sessions/101/manual-check-ins"), json)
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(queries, checkins);
  }

  @Test
  void overlongManualReasonPreservesServiceSemantic422() throws Exception {
    String reason = "x".repeat(501);
    var request = new ManualCheckinRequest(201L, 302L, reason);
    when(queries.manualRequest(21L, 101L, 201L, reason)).thenReturn(request);
    when(checkins.manualCheckin(21L, 101L, request)).thenThrow(error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MANUAL_CHECKIN_REASON"));
    perform(post("/api/v1/check-in-sessions/101/manual-check-ins"),
        "{\"registrationId\":201,\"reason\":\"" + reason + "\"}")
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_MANUAL_CHECKIN_REASON"));
  }

  private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request, String json) throws Exception {
    request.with(authentication(principal)).header("X-Request-Id", "checkin-request");
    if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
    return mvc.perform(request);
  }
  private SessionView session(String status) { return new SessionView(101L, 11L, "STATIC_CODE", status, now, now.plusMinutes(15)); }
  private CheckinResult result(String method) { return new CheckinResult(401L, 11L, 21L, method, now); }
  private BusinessException error(HttpStatus status, String code) { return new BusinessException(status, code, "Operation failed"); }
  private void noSecrets(String json) {
    assertThat(json).doesNotContain("ABCD2345", "staticCodeHash", "static_code_hash", "digest", "\"code\"", "manualReason");
  }
}
