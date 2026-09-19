package com.volunteerflow.registration;

import static com.volunteerflow.registration.PromotionService.conflict;
import static com.volunteerflow.registration.PromotionService.matchesOffer;
import static com.volunteerflow.registration.PromotionService.matchesPosition;
import static com.volunteerflow.registration.PromotionService.matchesRegistration;
import static com.volunteerflow.registration.PromotionService.notFound;
import static com.volunteerflow.registration.PromotionService.semantic;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.CancellationTiming;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.organization.OrganizationMemberMapper;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Cancels one active registration attempt and advances first-come promotion when needed. */
@Service
@Profile("!test")
public class RegistrationCancellationService {
  private static final Set<String> CANCELABLE = Set.of("PENDING_REVIEW", "CONFIRMED", "WAITLISTED");
  private final ActivityRegistrationPolicyService policies;
  private final OrganizationMemberMapper members;
  private final RegistrationMapper registrations;
  private final RegistrationCycleMapper cycles;
  private final PromotionOfferMapper offers;
  private final PromotionService promotions;
  private final AuditService audit;

  public RegistrationCancellationService(
      ActivityRegistrationPolicyService policies,
      OrganizationMemberMapper members,
      RegistrationMapper registrations,
      RegistrationCycleMapper cycles,
      PromotionOfferMapper offers,
      PromotionService promotions,
      AuditService audit) {
    this.policies = policies;
    this.members = members;
    this.registrations = registrations;
    this.cycles = cycles;
    this.offers = offers;
    this.promotions = promotions;
    this.audit = audit;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RegistrationCycle cancel(Long userId, Long registrationId, CancellationRequest request) {
    Registration registration = registrations.selectOwnedById(registrationId, userId);
    if (registration == null || !registration.getUserId().equals(userId)) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    RegistrationCycle snapshot = cycles.selectActiveOrLatest(registrationId);
    if (!matchesRegistration(snapshot, registration)
        || members.selectActiveMemberForUpdate(userId, registration.getOrganizationId()) == null) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    ActivityPosition position =
        policies.lockPositionForRegistrationChange(snapshot.getPositionId());
    if (!matchesPosition(snapshot, position)) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    RegistrationCycle cycle = cycles.selectActiveCycleForUpdate(registrationId);
    if (!matchesRegistration(cycle, registration) || !snapshot.getId().equals(cycle.getId())
        || !matchesPosition(cycle, position) || !CANCELABLE.contains(cycle.getStatus())) {
      throw conflict("REGISTRATION_NOT_ACTIVE", "Registration is no longer cancelable");
    }
    PromotionOffer offer = offers.selectByCycleForUpdate(cycle.getId());
    if (offer != null && !matchesOffer(offer, cycle)) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    CancellationTiming timing =
        policies.cancellationTiming(
            registration.getOrganizationId(), registration.getActivityId());
    LocalDateTime deadline =
        timing.freeCancelDeadlineAt() == null
            ? timing.activityStartAt()
            : timing.freeCancelDeadlineAt();
    if (deadline == null) {
      throw semantic(
          "INVALID_CANCELLATION_DEADLINE", "Activity cancellation deadline is unavailable");
    }
    LocalDateTime now = offers.currentDatabaseTime();
    boolean late = now.isAfter(deadline);
    String reason = request == null || request.reason() == null ? null : request.reason().trim();
    if (late && (reason == null || reason.isBlank())) {
      throw semantic("CANCELLATION_REASON_REQUIRED", "Late cancellation requires a reason");
    }
    if (reason != null && reason.length() > 500) {
      throw semantic("INVALID_CANCELLATION_REASON", "Reason must not exceed 500 characters");
    }
    boolean released = "CONFIRMED".equals(cycle.getStatus());
    if (offer != null && "PENDING".equals(offer.getStatus())) {
      // Even an already-expired PENDING offer must advance the queue here. Once canceled,
      // the expiry scanner will no longer see it and cannot select the next candidate.
      released = true;
      offer.setStatus("CANCELED");
      offer.setRespondedAt(now);
      if (offers.updateById(offer) != 1) {
        throw conflict("PROMOTION_OFFER_CONFLICT", "Invitation changed concurrently");
      }
    }
    cycle.setStatus(late ? "LATE_CANCELED" : "CANCELED");
    cycle.setCanceledAt(now);
    cycle.setCancelReason(reason);
    if (cycles.updateById(cycle) != 1) {
      throw conflict("REGISTRATION_CONFLICT", "Registration changed concurrently");
    }
    audit.record(
        registration.getOrganizationId(),
        userId,
        late ? "registration.late_canceled" : "registration.canceled",
        "registration_cycle",
        cycle.getId(),
        reason);
    if (released && "FIRST_COME".equals(position.getRegistrationMode())) {
      promotions.offerNextFirstCome(position.getId(), userId, "Registration canceled");
    }
    return cycle;
  }

  public record CancellationRequest(String reason) {}
}
