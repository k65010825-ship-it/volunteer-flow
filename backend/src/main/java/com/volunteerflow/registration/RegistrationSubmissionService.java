package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.RegistrationPolicy;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMemberMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes submissions by member first, then position, and persists the whole attempt atomically.
 */
@Service
@Profile("!test")
public class RegistrationSubmissionService {
  private final ActivityRegistrationPolicyService policies;
  private final OrganizationMemberMapper members;
  private final RegistrationMapper registrations;
  private final RegistrationCycleMapper cycles;
  private final RegistrationAnswerMapper answers;
  private final PromotionOfferMapper offers;
  private final RegistrationAnswerValidator validator;
  private final AuditService audit;
  private final Clock clock;

  public RegistrationSubmissionService(
      ActivityRegistrationPolicyService policies,
      OrganizationMemberMapper members,
      RegistrationMapper registrations,
      RegistrationCycleMapper cycles,
      RegistrationAnswerMapper answers,
      PromotionOfferMapper offers,
      RegistrationAnswerValidator validator,
      AuditService audit,
      Clock clock) {
    this.policies = policies;
    this.members = members;
    this.registrations = registrations;
    this.cycles = cycles;
    this.answers = answers;
    this.offers = offers;
    this.validator = validator;
    this.audit = audit;
    this.clock = clock;
  }

  // loadForm is a pre-lock read: READ_COMMITTED prevents it pinning stale capacity snapshots.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RegistrationResult submit(
      Long userId, Long activityId, SubmitRegistrationRequest request) {
    if (request == null || request.positionId() == null || request.positionId() <= 0) {
      throw semantic("INVALID_REGISTRATION_REQUEST", "A position is required");
    }
    try {
      return submitWithinTransaction(userId, activityId, request);
    } catch (DuplicateKeyException exception) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "REGISTRATION_CONFLICT",
          "Registration changed concurrently; refresh and retry");
    }
  }

  private RegistrationResult submitWithinTransaction(
      Long userId, Long activityId, SubmitRegistrationRequest request) {
    Long organizationId =
        policies.loadForm(userId, activityId, request.positionId()).activity().getOrganizationId();
    if (members.selectActiveMemberForUpdate(userId, organizationId) == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
    }
    RegistrationPolicy policy =
        policies.lockPolicyForSubmission(userId, activityId, request.positionId());
    LocalDateTime submittedAt = LocalDateTime.now(clock);
    requireOpenWindow(policy.activity(), submittedAt);

    Registration registration = registrations.selectByActivityAndUser(activityId, userId);
    if (registration != null && cycles.selectActiveCycleForUpdate(registration.getId()) != null) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "REGISTRATION_ALREADY_ACTIVE",
          "An active registration already exists for this activity");
    }
    validator.validate(policy.questions(), request.answers());
    registration = nextRegistration(registration, organizationId, activityId, userId);
    RegistrationCycle cycle = newCycle(registration, policy.position(), submittedAt);
    cycles.insert(cycle);
    saveAnswers(cycle, request.answers());
    audit.record(
        organizationId,
        userId,
        "registration.submitted",
        "registration_cycle",
        cycle.getId(),
        cycle.getStatus());

    Integer currentPosition =
        cycle.getWaitlistSequence() == null
            ? null
            : Math.toIntExact(
                cycles.countActiveWaitlistBefore(cycle.getPositionId(), cycle.getWaitlistSequence())
                    + 1);
    return new RegistrationResult(
        registration.getId(),
        cycle.getId(),
        cycle.getStatus(),
        cycle.getWaitlistSequence(),
        currentPosition);
  }

  private void requireOpenWindow(Activity activity, LocalDateTime now) {
    if (!"PUBLISHED".equals(activity.getStatus())) {
      throw semantic("ACTIVITY_NOT_PUBLISHED", "Activity is not open for registration");
    }
    if (activity.getRegistrationStartAt() == null
        || activity.getRegistrationEndAt() == null
        || now.isBefore(activity.getRegistrationStartAt())
        || !now.isBefore(activity.getRegistrationEndAt())) {
      throw semantic("REGISTRATION_WINDOW_CLOSED", "The registration window is closed");
    }
  }

  private Registration nextRegistration(
      Registration existing, Long organizationId, Long activityId, Long userId) {
    if (existing != null) {
      existing.setLastCycleNumber(Math.addExact(existing.getLastCycleNumber(), 1));
      registrations.updateById(existing);
      return existing;
    }
    Registration registration = new Registration();
    registration.setId(IdWorker.getId());
    registration.setOrganizationId(organizationId);
    registration.setActivityId(activityId);
    registration.setUserId(userId);
    registration.setLastCycleNumber(1);
    registration.setVersion(0);
    registrations.insert(registration);
    return registration;
  }

  private RegistrationCycle newCycle(
      Registration registration, ActivityPosition position, LocalDateTime submittedAt) {
    RegistrationCycle cycle = new RegistrationCycle();
    cycle.setId(IdWorker.getId());
    cycle.setOrganizationId(registration.getOrganizationId());
    cycle.setRegistrationId(registration.getId());
    cycle.setActivityId(registration.getActivityId());
    cycle.setPositionId(position.getId());
    cycle.setUserId(registration.getUserId());
    cycle.setCycleNumber(registration.getLastCycleNumber());
    cycle.setSubmittedAt(submittedAt);
    cycle.setVersion(0);
    if ("REVIEW".equals(position.getRegistrationMode())) {
      cycle.setStatus("PENDING_REVIEW");
    } else if ("FIRST_COME".equals(position.getRegistrationMode())) {
      long occupied =
          cycles.countConfirmed(position.getId())
              + offers.countActiveReservations(position.getId());
      if (occupied < position.getCapacity()) {
        cycle.setStatus("CONFIRMED");
      } else {
        cycle.setStatus("WAITLISTED");
        cycle.setWaitlistSequence(policies.allocateNextWaitlistSequence(position));
      }
    } else {
      throw semantic("INVALID_REGISTRATION_MODE", "Position registration mode is invalid");
    }
    return cycle;
  }

  private void saveAnswers(RegistrationCycle cycle, List<AnswerInput> inputs) {
    for (AnswerInput input : inputs) {
      RegistrationAnswer answer = new RegistrationAnswer();
      answer.setId(IdWorker.getId());
      answer.setOrganizationId(cycle.getOrganizationId());
      answer.setRegistrationCycleId(cycle.getId());
      answer.setQuestionScope(input.questionScope());
      answer.setQuestionId(input.questionId());
      answer.setAnswerJson(input.answer().toString());
      answers.insert(answer);
    }
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public record SubmitRegistrationRequest(
      @NotNull @Positive Long positionId, @NotNull List<@NotNull @Valid AnswerInput> answers) {}

  public record AnswerInput(
      @NotBlank String questionScope, @NotNull @Positive Long questionId, JsonNode answer) {}

  public record RegistrationResult(
      Long registrationId,
      Long cycleId,
      String status,
      Long waitlistSequence,
      Integer currentPosition) {}
}
