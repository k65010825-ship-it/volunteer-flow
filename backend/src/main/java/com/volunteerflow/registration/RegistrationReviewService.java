package com.volunteerflow.registration;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityPositionMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies organization-scoped manual decisions using the shared member-position-cycle lock order.
 */
@Service
@Profile("!test")
public class RegistrationReviewService {
  private static final Set<String> ALLOWED_DECISIONS = Set.of("CONFIRM", "WAITLIST", "REJECT");

  private final RegistrationMapper registrations;
  private final RegistrationCycleMapper cycles;
  private final ActivityPositionMapper positions;
  private final OrganizationMemberMapper members;
  private final PromotionOfferMapper offers;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;
  private final Clock clock;

  public RegistrationReviewService(
      RegistrationMapper registrations,
      RegistrationCycleMapper cycles,
      ActivityPositionMapper positions,
      OrganizationMemberMapper members,
      PromotionOfferMapper offers,
      OrganizationAuthorizationService authorization,
      AuditService audit,
      Clock clock) {
    this.registrations = registrations;
    this.cycles = cycles;
    this.positions = positions;
    this.members = members;
    this.offers = offers;
    this.authorization = authorization;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RegistrationCycle decide(
      Long actorId, Long registrationId, ReviewDecisionRequest request) {
    String decision = normalizedDecision(request);
    String reason = normalizedReason(request.reason());
    if (actorId == null || registrationId == null || registrationId <= 0) {
      throw semantic("INVALID_REVIEW_REQUEST", "Actor and registration are required");
    }

    Registration registration = registrations.selectById(registrationId);
    if (registration == null) {
      throw registrationNotFound();
    }
    RegistrationCycle snapshot = cycles.selectActiveOrLatest(registrationId);
    if (!matchesRegistration(snapshot, registration)) {
      throw registrationNotFound();
    }

    authorization.requirePermission(
        actorId, registration.getOrganizationId(), "registration:review");
    if (members.selectActiveMemberForUpdate(
            registration.getUserId(), registration.getOrganizationId())
        == null) {
      throw registrationNotFound();
    }

    ActivityPosition position = positions.selectByIdForUpdate(snapshot.getPositionId());
    if (!matchesRegistration(position, registration, snapshot)) {
      throw registrationNotFound();
    }
    if (!"REVIEW".equals(position.getRegistrationMode())) {
      throw conflict(
          "POSITION_NOT_REVIEW_MODE", "Registration decisions require a review position");
    }

    RegistrationCycle cycle = cycles.selectActiveCycleForUpdate(registrationId);
    if (!matchesLockedCycle(cycle, registration, position, snapshot)
        || !"PENDING_REVIEW".equals(cycle.getStatus())) {
      throw conflict("REGISTRATION_NOT_PENDING_REVIEW", "Registration is no longer pending review");
    }

    String resultingStatus = resultingStatus(decision, position);
    cycle.setStatus(resultingStatus);
    cycle.setWaitlistSequence(null);
    cycle.setReviewedBy(actorId);
    cycle.setReviewedAt(LocalDateTime.now(clock));
    cycle.setReviewReason(reason);
    cycles.updateById(cycle);
    audit.record(
        registration.getOrganizationId(),
        actorId,
        "registration.reviewed",
        "registration_cycle",
        cycle.getId(),
        resultingStatus);
    return cycle;
  }

  private String normalizedDecision(ReviewDecisionRequest request) {
    if (request == null || request.decision() == null) {
      throw semantic("INVALID_REVIEW_DECISION", "A review decision is required");
    }
    String decision = request.decision().trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_DECISIONS.contains(decision)) {
      throw semantic("INVALID_REVIEW_DECISION", "Decision must be CONFIRM, WAITLIST, or REJECT");
    }
    return decision;
  }

  private String normalizedReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw semantic("INVALID_REVIEW_REASON", "A review reason is required");
    }
    String normalized = reason.trim();
    if (normalized.length() > 500) {
      throw semantic("INVALID_REVIEW_REASON", "Review reason must not exceed 500 characters");
    }
    return normalized;
  }

  private String resultingStatus(String decision, ActivityPosition position) {
    if ("CONFIRM".equals(decision)) {
      long occupied =
          Math.addExact(
              cycles.countConfirmed(position.getId()),
              offers.countActiveReservations(position.getId()));
      if (occupied >= position.getCapacity()) {
        throw conflict("POSITION_CAPACITY_FULL", "Position capacity is full");
      }
      return "CONFIRMED";
    }
    return "WAITLIST".equals(decision) ? "WAITLISTED" : "REJECTED";
  }

  private boolean matchesRegistration(RegistrationCycle cycle, Registration registration) {
    return cycle != null
        && registration.getId().equals(cycle.getRegistrationId())
        && registration.getOrganizationId().equals(cycle.getOrganizationId())
        && registration.getActivityId().equals(cycle.getActivityId())
        && registration.getUserId().equals(cycle.getUserId())
        && cycle.getPositionId() != null;
  }

  private boolean matchesRegistration(
      ActivityPosition position, Registration registration, RegistrationCycle snapshot) {
    return position != null
        && position.getId().equals(snapshot.getPositionId())
        && registration.getOrganizationId().equals(position.getOrganizationId())
        && registration.getActivityId().equals(position.getActivityId())
        && "ACTIVE".equals(position.getStatus())
        && position.getCapacity() != null
        && position.getCapacity() >= 0;
  }

  private boolean matchesLockedCycle(
      RegistrationCycle cycle,
      Registration registration,
      ActivityPosition position,
      RegistrationCycle snapshot) {
    return matchesRegistration(cycle, registration)
        && snapshot.getId().equals(cycle.getId())
        && position.getId().equals(cycle.getPositionId());
  }

  private BusinessException registrationNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND", "Registration was not found");
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  public record ReviewDecisionRequest(@NotBlank String decision, String reason) {}
}
