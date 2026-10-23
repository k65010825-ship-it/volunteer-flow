package com.volunteerflow.activity;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Management-only command adapter; existing activity read/update adapters remain unchanged. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/activities")
public class ActivityCompletionController {
  private final ActivityCompletionService service;

  public ActivityCompletionController(ActivityCompletionService service) {
    this.service = service;
  }

  @PostMapping("/{id}/completion")
  public ApiResponse<Activity> complete(
      @AuthenticationPrincipal CurrentUser user,
      @PathVariable("id") Long activityId,
      @Valid @RequestBody CompletionRequest request) {
    return ApiResponse.of(service.complete(user.id(), activityId, request.reason()));
  }

  public record CompletionRequest(@NotBlank @Size(max = 500) String reason) {}
}
