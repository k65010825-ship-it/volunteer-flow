package com.volunteerflow.registration;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.infrastructure.event.DomainEventCommand;
import com.volunteerflow.infrastructure.event.DomainEventPublisher;
import com.volunteerflow.infrastructure.event.DomainEventTypes;
import com.volunteerflow.infrastructure.event.NotificationIntentPayload;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Finalizes one expired reservation while preserving the global member-position-cycle-offer order. */
@Service
@Profile("!test")
public class PromotionExpiryService {
  private final PromotionOfferMapper offers;
  private final RegistrationCycleMapper cycles;
  private final OrganizationMemberMapper members;
  private final ActivityRegistrationPolicyService policies;
  private final PromotionService promotions;
  private final AuditService audit;
  private final DomainEventPublisher events;
  private final PromotionReconciliationRequester reconciliation;

  public PromotionExpiryService(
      PromotionOfferMapper offers,
      RegistrationCycleMapper cycles,
      OrganizationMemberMapper members,
      ActivityRegistrationPolicyService policies,
      PromotionService promotions,
      AuditService audit,
      DomainEventPublisher events,
      PromotionReconciliationRequester reconciliation) {
    this.offers = offers;
    this.cycles = cycles;
    this.members = members;
    this.policies = policies;
    this.promotions = promotions;
    this.audit = audit;
    this.events = events;
    this.reconciliation = reconciliation;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public boolean expireOne(Long offerId) {
    // Snapshot reads discover lock keys only. All mutable decisions use locked rows below.
    PromotionOffer snapshot = offers.selectById(offerId);
    RegistrationCycle cycleSnapshot =
        snapshot == null ? null : cycles.selectById(snapshot.getRegistrationCycleId());
    if (!PromotionService.matchesOffer(snapshot, cycleSnapshot)) {
      return false;
    }
    // Expiry is cleanup, not authorization: deactivation must not strand a reservation.
    OrganizationMember member = members.selectMemberForUpdateAnyStatus(
        cycleSnapshot.getUserId(), cycleSnapshot.getOrganizationId());
    if (member == null
        || !Objects.equals(member.getUserId(), cycleSnapshot.getUserId())
        || !Objects.equals(member.getOrganizationId(), cycleSnapshot.getOrganizationId())) {
      return false;
    }
    ActivityPosition position =
        policies.lockPositionForRegistrationChange(cycleSnapshot.getPositionId());
    RegistrationCycle cycle = cycles.selectByIdForUpdate(cycleSnapshot.getId());
    PromotionOffer offer = offers.selectByIdForUpdate(offerId);
    if (!PromotionService.matchesPosition(cycle, position)
        || !PromotionService.matchesOffer(offer, cycle)
        || !Objects.equals(cycleSnapshot.getId(), cycle.getId())
        || !Objects.equals(cycleSnapshot.getUserId(), cycle.getUserId())
        || !Objects.equals(cycleSnapshot.getOrganizationId(), cycle.getOrganizationId())
        || !Objects.equals(cycleSnapshot.getActivityId(), cycle.getActivityId())
        || !Objects.equals(cycleSnapshot.getRegistrationId(), cycle.getRegistrationId())
        || !"PENDING".equals(offer.getStatus())
        || !"WAITLISTED".equals(cycle.getStatus())) {
      return false;
    }
    LocalDateTime now = offers.currentDatabaseTime();
    if (offer.getExpiresAt() == null || now.isBefore(offer.getExpiresAt())) {
      return false;
    }
    offer.setStatus("EXPIRED");
    offer.setRespondedAt(now);
    cycle.setStatus("PROMOTION_EXPIRED");
    cycle.setAttendanceStatus("NOT_REQUIRED");
    if (offers.updateById(offer) != 1 || cycles.updateById(cycle) != 1) {
      throw PromotionService.conflict(
          "PROMOTION_OFFER_CONFLICT", "Invitation changed concurrently");
    }
    audit.record(cycle.getOrganizationId(), null, "promotion.expired", "promotion_offer",
        offerId, "PROMOTION_EXPIRED");
    events.publish(new DomainEventCommand(
        cycle.getOrganizationId(), cycle.getActivityId(), "PROMOTION_OFFER", offerId.toString(),
        DomainEventTypes.REGISTRATION_PROMOTION_EXPIRED, new NotificationIntentPayload(
            cycle.getUserId(), "PROMOTION_OFFER", "递补邀请已过期", "递补邀请已过期", "PROMOTION_OFFER", offerId)));
    reconciliation.request(cycle.getOrganizationId(), position.getId(), now);
    if ("FIRST_COME".equals(position.getRegistrationMode())) {
      promotions.offerNextFirstCome(position.getId(), null, "Previous invitation expired");
    }
    return true;
  }
}
