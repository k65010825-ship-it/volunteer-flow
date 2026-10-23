package com.volunteerflow.activity;

import com.volunteerflow.checkin.ActivityFinalizationService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit management command, serialized with registration confirmation and check-in. */
@Service
@Profile("!test")
public class ActivityCompletionService {
  private final ActivityMapper activities;
  private final OrganizationAuthorizationService authorization;
  private final ActivityFinalizationService finalization;

  public ActivityCompletionService(ActivityMapper activities,
      OrganizationAuthorizationService authorization, ActivityFinalizationService finalization) {
    this.activities = activities;
    this.authorization = authorization;
    this.finalization = finalization;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Activity complete(Long actorId, Long activityId, String reason) {
    Activity activity = activities.selectByIdForUpdate(activityId);
    if (activity == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND", "Activity was not found");
    }
    // Authorize even terminal retries: idempotency must not disclose another organization's data.
    authorization.requirePermission(actorId, activity.getOrganizationId(), "activity:change");
    if (reason == null || reason.isBlank() || reason.length() > 500) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "A reason of 1 to 500 characters is required");
    }
    if ("FINISHED".equals(activity.getStatus())) {
      return activity;
    }
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw new BusinessException(HttpStatus.CONFLICT, "ACTIVITY_COMPLETION_NOT_ALLOWED", "Only a published activity can be completed");
    }
    finalization.finalizeManually(activity, actorId, reason.trim());
    return activity;
  }
}
