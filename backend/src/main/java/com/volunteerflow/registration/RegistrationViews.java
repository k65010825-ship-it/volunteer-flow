package com.volunteerflow.registration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** API-safe registration projections for member and organization management screens. */
public final class RegistrationViews {
  private RegistrationViews() {}

  public record AnswerView(String questionScope, Long questionId, JsonNode answer) {}

  public record OwnOfferView(Long id, String status, LocalDateTime expiresAt, String reason) {}

  public record ManagedOfferView(
      Long id,
      String status,
      LocalDateTime expiresAt,
      LocalDateTime respondedAt,
      Long createdBy,
      String reason) {}

  public record OwnRegistrationView(
      Long registrationId,
      Long organizationId,
      Long activityId,
      Long cycleId,
      Long positionId,
      Integer cycleNumber,
      String status,
      LocalDateTime submittedAt,
      Long waitlistSequence,
      Integer currentWaitlistPosition,
      Long waitlistCount,
      List<AnswerView> answers,
      OwnOfferView pendingOffer) {}

  public record ManagedRegistrationView(
      Long registrationId,
      Long organizationId,
      Long activityId,
      Long cycleId,
      Long positionId,
      Integer cycleNumber,
      Long userId,
      String realName,
      String studentNumber,
      String contact,
      String status,
      Long waitlistSequence,
      LocalDateTime submittedAt,
      Long reviewedBy,
      LocalDateTime reviewedAt,
      String reviewReason,
      List<AnswerView> answers,
      ManagedOfferView offer) {}

  public record ManagedRegistrationPage(
      List<ManagedRegistrationView> items, long total, long page, long size) {}
}
