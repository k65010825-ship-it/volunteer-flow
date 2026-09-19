package com.volunteerflow.registration;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and resolves seat reservations while serializing capacity changes on the position row.
 * Offer, cycle, and audit writes always share one transaction.
 */
@Service
@Profile("!test")
public class PromotionService {
  private final ActivityRegistrationPolicyService policies;
  private final OrganizationMemberMapper members;
  private final RegistrationMapper registrations;
  private final RegistrationCycleMapper cycles;
  private final PromotionOfferMapper offers;
  private final OrganizationAuthorizationService authorization;
  private final AuditService audit;

  public PromotionService(
      ActivityRegistrationPolicyService policies,
      OrganizationMemberMapper members,
      RegistrationMapper registrations,
      RegistrationCycleMapper cycles,
      PromotionOfferMapper offers,
      OrganizationAuthorizationService authorization,
      AuditService audit) {
    this.policies = policies;
    this.members = members;
    this.registrations = registrations;
    this.cycles = cycles;
    this.offers = offers;
    this.authorization = authorization;
    this.audit = audit;
  }

  /**
   * Internal callers already hold the position lock. Do not lock another member afterward:
   * automatic selection changes no membership and must never reverse the member-position order.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PromotionOffer offerNextFirstCome(Long positionId, Long createdBy, String reason) {
    ActivityPosition position = policies.lockPositionForRegistrationChange(positionId);
    if (position == null || !"ACTIVE".equals(position.getStatus())
        || !"FIRST_COME".equals(position.getRegistrationMode()) || !hasCapacity(position)) {
      return null;
    }
    RegistrationCycle cycle = cycles.selectFirstWaitlistedForUpdate(positionId);
    if (cycle == null) {
      return null;
    }
    if (!matchesPosition(cycle, position) || !"WAITLISTED".equals(cycle.getStatus())
        || cycle.getWaitlistSequence() == null) {
      throw conflict("REGISTRATION_NOT_WAITLISTED", "Candidate is no longer waitlisted");
    }
    return createOffer(position, cycle, createdBy, normalizeReason(reason));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PromotionOffer createManualOffer(
      Long actorId, Long registrationId, PromotionRequest request) {
    String reason = normalizeReason(request == null ? null : request.reason());
    Registration registration = registrations.selectById(registrationId);
    if (registration == null) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    authorization.requirePermission(
        actorId, registration.getOrganizationId(), "registration:promote");
    RegistrationCycle snapshot = cycles.selectActiveOrLatest(registrationId);
    if (!matchesRegistration(snapshot, registration)) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    requireMember(
        registration.getUserId(), registration.getOrganizationId(), "REGISTRATION_NOT_FOUND");
    ActivityPosition position =
        policies.lockPositionForRegistrationChange(snapshot.getPositionId());
    if (!matchesPosition(snapshot, position) || !"ACTIVE".equals(position.getStatus())) {
      throw notFound("REGISTRATION_NOT_FOUND");
    }
    if (!"REVIEW".equals(position.getRegistrationMode())) {
      throw conflict("POSITION_NOT_REVIEW_MODE", "Manual offers require a review position");
    }
    RegistrationCycle cycle = cycles.selectActiveCycleForUpdate(registrationId);
    if (!matchesRegistration(cycle, registration) || !snapshot.getId().equals(cycle.getId())
        || !matchesPosition(cycle, position) || !"WAITLISTED".equals(cycle.getStatus())
        || cycle.getWaitlistSequence() != null) {
      throw conflict("REGISTRATION_NOT_WAITLISTED", "Candidate is no longer in the review pool");
    }
    if (offers.selectByCycleForUpdate(cycle.getId()) != null) {
      throw conflict("PROMOTION_OFFER_CONFLICT", "This attempt already has an invitation");
    }
    if (!hasCapacity(position)) {
      throw conflict("POSITION_CAPACITY_FULL", "Position capacity is full");
    }
    return createOffer(position, cycle, actorId, reason);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PromotionOffer respond(Long userId, Long offerId, PromotionResponseRequest request) {
    String decision = normalizeDecision(request);
    PromotionOffer snapshot = offers.selectById(offerId);
    RegistrationCycle cycleSnapshot =
        snapshot == null ? null : cycles.selectById(snapshot.getRegistrationCycleId());
    if (cycleSnapshot == null || userId == null || !userId.equals(cycleSnapshot.getUserId())
        || !matchesOffer(snapshot, cycleSnapshot)) {
      throw notFound("PROMOTION_OFFER_NOT_FOUND");
    }
    requireMember(userId, cycleSnapshot.getOrganizationId(), "PROMOTION_OFFER_NOT_FOUND");
    ActivityPosition position =
        policies.lockPositionForRegistrationChange(cycleSnapshot.getPositionId());
    RegistrationCycle cycle = cycles.selectByIdForUpdate(cycleSnapshot.getId());
    PromotionOffer offer = offers.selectByIdForUpdate(offerId);
    if (cycle == null || !userId.equals(cycle.getUserId()) || !matchesPosition(cycle, position)
        || !"ACTIVE".equals(position.getStatus()) || !matchesOffer(offer, cycle)
        || !snapshot.getRegistrationCycleId().equals(cycle.getId())
        || !cycleSnapshot.getRegistrationId().equals(cycle.getRegistrationId())) {
      throw notFound("PROMOTION_OFFER_NOT_FOUND");
    }
    if (!"PENDING".equals(offer.getStatus())) {
      throw conflict("PROMOTION_OFFER_NOT_PENDING", "Invitation was already handled");
    }
    LocalDateTime now = offers.currentDatabaseTime();
    if (!now.isBefore(offer.getExpiresAt())) {
      throw conflict("PROMOTION_OFFER_EXPIRED", "Invitation has expired");
    }
    if (!"WAITLISTED".equals(cycle.getStatus())) {
      throw conflict("REGISTRATION_NOT_WAITLISTED", "Registration is no longer waitlisted");
    }
    boolean accepted = "ACCEPT".equals(decision);
    offer.setStatus(accepted ? "ACCEPTED" : "DECLINED");
    offer.setRespondedAt(now);
    cycle.setStatus(accepted ? "CONFIRMED" : "PROMOTION_DECLINED");
    if (offers.updateById(offer) != 1 || cycles.updateById(cycle) != 1) {
      throw conflict("PROMOTION_OFFER_CONFLICT", "Invitation changed concurrently");
    }
    audit.record(
        cycle.getOrganizationId(),
        userId,
        accepted ? "promotion.accepted" : "promotion.declined",
        "promotion_offer",
        offer.getId(),
        cycle.getStatus());
    if (!accepted && "FIRST_COME".equals(position.getRegistrationMode())) {
      offerNextFirstCome(position.getId(), userId, "Previous invitation declined");
    }
    return offer;
  }

  private PromotionOffer createOffer(
      ActivityPosition position, RegistrationCycle cycle, Long actorId, String reason) {
    if (position.getPromotionTimeoutMinutes() == null
        || position.getPromotionTimeoutMinutes() <= 0) {
      throw semantic("INVALID_PROMOTION_TIMEOUT", "Promotion timeout must be positive");
    }
    PromotionOffer offer = new PromotionOffer();
    offer.setId(IdWorker.getId());
    offer.setOrganizationId(cycle.getOrganizationId());
    offer.setActivityId(cycle.getActivityId());
    offer.setPositionId(position.getId());
    offer.setRegistrationCycleId(cycle.getId());
    offer.setStatus("PENDING");
    offer.setExpiresAt(
        offers.currentDatabaseTime().plusMinutes(position.getPromotionTimeoutMinutes()));
    offer.setCreatedBy(actorId);
    offer.setReason(reason);
    offer.setVersion(0);
    try {
      if (offers.insert(offer) != 1) {
        throw conflict("PROMOTION_OFFER_CONFLICT", "Invitation could not be created");
      }
    } catch (DuplicateKeyException exception) {
      throw conflict("PROMOTION_OFFER_CONFLICT", "This attempt already has an invitation");
    }
    audit.record(
        cycle.getOrganizationId(),
        actorId,
        "promotion.offered",
        "promotion_offer",
        offer.getId(),
        reason);
    return offer;
  }

  private boolean hasCapacity(ActivityPosition position) {
    return position.getCapacity() != null
        && Math.addExact(
                cycles.countConfirmed(position.getId()),
                offers.countActiveReservations(position.getId()))
            < position.getCapacity();
  }

  private void requireMember(Long userId, Long organizationId, String code) {
    if (members.selectActiveMemberForUpdate(userId, organizationId) == null) {
      throw notFound(code);
    }
  }

  static boolean matchesRegistration(RegistrationCycle cycle, Registration registration) {
    return cycle != null
        && registration != null
        && registration.getId().equals(cycle.getRegistrationId())
        && registration.getOrganizationId().equals(cycle.getOrganizationId())
        && registration.getActivityId().equals(cycle.getActivityId())
        && registration.getUserId().equals(cycle.getUserId());
  }

  static boolean matchesPosition(RegistrationCycle cycle, ActivityPosition position) {
    return cycle != null
        && position != null
        && position.getId().equals(cycle.getPositionId())
        && position.getOrganizationId().equals(cycle.getOrganizationId())
        && position.getActivityId().equals(cycle.getActivityId());
  }

  static boolean matchesOffer(PromotionOffer offer, RegistrationCycle cycle) {
    return offer != null
        && cycle != null
        && cycle.getId().equals(offer.getRegistrationCycleId())
        && cycle.getOrganizationId().equals(offer.getOrganizationId())
        && cycle.getActivityId().equals(offer.getActivityId())
        && cycle.getPositionId().equals(offer.getPositionId());
  }

  private String normalizeDecision(PromotionResponseRequest request) {
    String decision =
        request == null || request.decision() == null
            ? ""
            : request.decision().trim().toUpperCase(Locale.ROOT);
    if (!"ACCEPT".equals(decision) && !"DECLINE".equals(decision)) {
      throw semantic("INVALID_PROMOTION_RESPONSE", "Response must be ACCEPT or DECLINE");
    }
    return decision;
  }

  private String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    String normalized = reason.trim();
    if (normalized.length() > 500) {
      throw semantic("INVALID_PROMOTION_REASON", "Reason must not exceed 500 characters");
    }
    return normalized;
  }

  static BusinessException notFound(String code) {
    return new BusinessException(HttpStatus.NOT_FOUND, code, "Registration resource was not found");
  }

  static BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  static BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public record PromotionRequest(String reason) {}

  public record PromotionResponseRequest(@NotBlank String decision) {}
}
