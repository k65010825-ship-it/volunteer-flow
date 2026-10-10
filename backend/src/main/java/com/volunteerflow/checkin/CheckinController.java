package com.volunteerflow.checkin;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.checkin.CheckinService.CheckinResult;
import com.volunteerflow.checkin.CheckinSessionService.OpenSessionRequest;
import com.volunteerflow.checkin.CheckinSessionService.OpenedSession;
import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import com.volunteerflow.checkin.CheckinViews.AttendanceSummary;
import com.volunteerflow.checkin.CheckinViews.CurrentCheckin;
import com.volunteerflow.infrastructure.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class CheckinController {
  private final CheckinSessionService sessions;
  private final CheckinService checkins;
  private final AttendanceQueryService queries;

  public CheckinController(CheckinSessionService sessions, CheckinService checkins, AttendanceQueryService queries) {
    this.sessions = sessions; this.checkins = checkins; this.queries = queries;
  }

  @PostMapping("/activities/{activityId}/check-in-sessions")
  public ResponseEntity<ApiResponse<OpenedSession>> open(@AuthenticationPrincipal CurrentUser user,
      @PathVariable Long activityId, @Valid @RequestBody(required = false) OpenSessionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(sessions.open(user.id(), activityId, request)));
  }

  @PostMapping("/check-in-sessions/{sessionId}/closure")
  public ApiResponse<SessionView> close(@AuthenticationPrincipal CurrentUser user, @PathVariable Long sessionId) {
    return ApiResponse.of(sessions.close(user.id(), sessionId));
  }

  @GetMapping("/activities/{activityId}/check-in-sessions/current")
  public ApiResponse<CurrentCheckin> current(@AuthenticationPrincipal CurrentUser user, @PathVariable Long activityId) {
    return ApiResponse.of(queries.current(user.id(), activityId));
  }

  @PostMapping("/check-in-sessions/{sessionId}/check-ins")
  public ResponseEntity<ApiResponse<CheckinResult>> selfCheckin(@AuthenticationPrincipal CurrentUser user,
      @PathVariable Long sessionId, @Valid @RequestBody SelfCheckinInput request) {
    var command = queries.selfRequest(user.id(), sessionId, request.code());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(checkins.selfCheckin(user.id(), sessionId, command)));
  }

  @PostMapping("/check-in-sessions/{sessionId}/manual-check-ins")
  public ResponseEntity<ApiResponse<CheckinResult>> manualCheckin(@AuthenticationPrincipal CurrentUser user,
      @PathVariable Long sessionId, @Valid @RequestBody ManualCheckinInput request) {
    var command = queries.manualRequest(user.id(), sessionId, request.registrationId(), request.reason());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(checkins.manualCheckin(user.id(), sessionId, command)));
  }

  @GetMapping("/activities/{activityId}/attendance-summary")
  public ApiResponse<AttendanceSummary> summary(@AuthenticationPrincipal CurrentUser user, @PathVariable Long activityId) {
    return ApiResponse.of(queries.summary(user.id(), activityId));
  }

  // HTTP contracts intentionally omit activity/cycle/actor IDs. Commands resolve them server-side.
  public record SelfCheckinInput(@NotBlank String code) {
    @Override public String toString() { return "SelfCheckinInput[code=[REDACTED]]"; }
  }
  public record ManualCheckinInput(@NotNull Long registrationId, @NotBlank String reason) {}
}
