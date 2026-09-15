package com.volunteerflow.activity;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for activity lifecycle and position-management operations. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class ActivityController {
  private final ActivityService service;

  public ActivityController(ActivityService service) {
    this.service = service;
  }

  @PostMapping("/organizations/{orgId}/activities")
  public ResponseEntity<ApiResponse<Activity>> create(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long orgId,
      @Valid @RequestBody ActivityService.ActivityRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.create(currentUser.id(), orgId, request)));
  }

  @PutMapping("/activities/{id}")
  public ApiResponse<Activity> update(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @Valid @RequestBody ActivityService.ActivityRequest request) {
    return ApiResponse.of(service.update(currentUser.id(), id, request));
  }

  @PostMapping("/activities/{id}/positions")
  public ResponseEntity<ApiResponse<ActivityPosition>> position(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @Valid @RequestBody ActivityService.PositionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(service.addPosition(currentUser.id(), id, request)));
  }

  @PostMapping("/activities/{id}/publication")
  public ApiResponse<Activity> publish(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.publish(currentUser.id(), id));
  }

  @PostMapping("/activities/{id}/cancellation")
  public ApiResponse<Activity> cancel(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long id,
      @Valid @RequestBody ActivityService.CancelRequest request) {
    return ApiResponse.of(service.cancel(currentUser.id(), id, request.reason()));
  }

  @GetMapping("/organizations/{orgId}/activities")
  public ApiResponse<List<ActivityService.ActivitySummary>> list(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long orgId) {
    return ApiResponse.of(service.list(currentUser.id(), orgId));
  }

  @GetMapping("/activities/{id}")
  public ApiResponse<ActivityService.ActivityDetail> detail(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return ApiResponse.of(service.detail(currentUser.id(), id));
  }
}
