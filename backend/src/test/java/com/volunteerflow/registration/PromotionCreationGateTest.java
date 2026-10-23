package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.*;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs real promotion/cleanup services and policy reads with mapper boundaries; no live DB. */
class PromotionCreationGateTest {
  static Stream<Arguments> closedActions() {
    return Stream.of("FINISHED", "CANCELED", "TIME_ENDED").flatMap(status ->
        Stream.of("DECLINE", "EXPIRE", "CANCEL").map(action -> Arguments.of(status, action)));
  }

  @ParameterizedTest
  @MethodSource("closedActions")
  void terminalOfferCleanupStillCompletesButNeverInvitesNextCandidate(String status, String action) {
    Fixture f = new Fixture(status);
    f.cleanup(action);
    assertThat(f.cycle.getStatus()).isEqualTo(switch (action) {
      case "DECLINE" -> "PROMOTION_DECLINED";
      case "EXPIRE" -> "PROMOTION_EXPIRED";
      default -> status.equals("TIME_ENDED") ? "LATE_CANCELED" : "CANCELED";
    });
    verify(f.offers, never()).insert(any(PromotionOffer.class));
    assertThat(f.events.commands()).noneMatch(event -> event.eventType().equals("registration.promotion-offered.v1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CANCELED", "TIME_ENDED", "OTHER_ORGANIZATION", "MISSING"})
  void manualOfferRejectsAnActivityOutsideTheOpenCreationBoundary(String status) {
    Fixture f = new Fixture(status);
    f.position.setRegistrationMode("REVIEW");
    f.cycle.setWaitlistSequence(null);
    when(f.offers.selectByCycleForUpdate(101L)).thenReturn(null);
    assertThatThrownBy(() -> f.promotions.createManualOffer(7L, 100L,
        new PromotionService.PromotionRequest("selected")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("ACTIVITY_NOT_OPEN_FOR_CONFIRMATION");
    verify(f.offers, never()).insert(any(PromotionOffer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CANCELED", "TIME_ENDED", "OTHER_ORGANIZATION", "MISSING"})
  void bothAutomaticCreationEntrypointsReturnNoOfferForClosedActivity(String status) {
    Fixture f = new Fixture(status);
    assertThat(f.promotions.offerNextFirstCome(20L, null, "released")).isNull();
    assertThat(f.promotions.fillAvailableFirstComeSeats(f.position, null, "capacity")).isZero();
    verify(f.offers, never()).insert(any(PromotionOffer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINE", "EXPIRE", "CANCEL"})
  void publishedFutureActivityKeepsFirstComeAutomaticPromotion(String action) {
    Fixture f = new Fixture("PUBLISHED");
    f.cleanup(action);
    verify(f.offers).insert(any(PromotionOffer.class));
    assertThat(f.events.commands()).anyMatch(event -> event.eventType().equals("registration.promotion-offered.v1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"NEXT", "FILL"})
  void rechecksAfterCandidateLockAndNeverCountsAnOfferRejectedAtTheFinalGate(String action) {
    Fixture f = new Fixture("PUBLISHED");
    Activity closed = f.activity("FINISHED");
    when(f.activities.selectByIdForSubmissionRevalidation(11L)).thenReturn(f.activity, closed);
    if (action.equals("NEXT")) {
      assertThat(f.promotions.offerNextFirstCome(20L, null, "released")).isNull();
    } else {
      assertThat(f.promotions.fillAvailableFirstComeSeats(f.position, null, "capacity")).isZero();
    }
    verify(f.cycles).selectFirstWaitlistedForUpdate(20L);
    verify(f.offers, never()).insert(any(PromotionOffer.class));
  }

  private static final class Fixture {
    final LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
    final ActivityMapper activities = mock(ActivityMapper.class);
    final ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
    final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
    final RegistrationMapper registrations = mock(RegistrationMapper.class);
    final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
    final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
    final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
    final AuditService audit = mock(AuditService.class);
    final CapturingDomainEventPublisher events = new CapturingDomainEventPublisher();
    final ActivityPosition position = PromotionServiceTest.position();
    final RegistrationCycle cycle = PromotionServiceTest.cycle(101L, 100L, 21L);
    final PromotionOffer offer = new PromotionOffer();
    final Activity activity;
    final PromotionService promotions;
    final PromotionExpiryService expiry;
    final RegistrationCancellationService cancellation;

    Fixture(String status) {
      activity = activity(status);
      when(activities.selectByIdForSubmissionRevalidation(11L)).thenReturn(status.equals("MISSING") ? null : activity);
      // Cleanup routing still identifies the existing activity even when the new-offer read rejects it.
      when(activities.selectByIdForUpdate(11L)).thenReturn(activity("FINISHED".equals(status) ? status : "PUBLISHED"));
      when(positions.selectByIdForUpdate(20L)).thenReturn(position);
      ActivityRegistrationPolicyService policies = new ActivityRegistrationPolicyService(activities,
          positions, mock(ActivityQuestionMapper.class), mock(ActivityPositionQuestionMapper.class),
          authorization, new ObjectMapper());
      Registration registration = PromotionServiceTest.registration(100L, 21L);
      when(registrations.selectById(100L)).thenReturn(registration);
      when(registrations.selectOwnedById(100L, 21L)).thenReturn(registration);
      when(cycles.selectActiveOrLatest(100L)).thenReturn(cycle);
      when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(cycle);
      when(cycles.selectById(101L)).thenReturn(cycle);
      when(cycles.selectByIdForUpdate(101L)).thenReturn(cycle);
      RegistrationCycle next = PromotionServiceTest.cycle(102L, 110L, 22L);
      next.setWaitlistSequence(2L);
      when(cycles.selectFirstWaitlistedForUpdate(20L)).thenReturn(next).thenReturn(null);
      when(cycles.updateById(any(RegistrationCycle.class))).thenReturn(1);
      OrganizationMember member = new OrganizationMember();
      member.setOrganizationId(10L); member.setUserId(21L);
      when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(member);
      when(members.selectMemberForUpdateAnyStatus(21L, 10L)).thenReturn(member);
      offer.setId(300L); offer.setOrganizationId(10L); offer.setActivityId(11L);
      offer.setPositionId(20L); offer.setRegistrationCycleId(101L); offer.setStatus("PENDING");
      offer.setExpiresAt(now.plusMinutes(30));
      when(offers.selectById(300L)).thenReturn(offer);
      when(offers.selectByIdForUpdate(300L)).thenReturn(offer);
      when(offers.selectByCycleForUpdate(101L)).thenReturn(offer);
      when(offers.currentDatabaseTime()).thenReturn(now);
      when(offers.updateById(any(PromotionOffer.class))).thenReturn(1);
      when(offers.insert(any(PromotionOffer.class))).thenReturn(1);
      var reconciliation = mock(PromotionReconciliationRequester.class);
      promotions = new PromotionService(policies, members, registrations, cycles, offers,
          authorization, audit, events, reconciliation);
      expiry = new PromotionExpiryService(offers, cycles, members, policies, promotions,
          audit, events, reconciliation);
      cancellation = new RegistrationCancellationService(policies, members, registrations, cycles,
          offers, promotions, audit, events, reconciliation);
    }

    Activity activity(String status) {
      Activity value = new Activity();
      value.setId(11L); value.setOrganizationId(status.equals("OTHER_ORGANIZATION") ? 99L : 10L);
      value.setStatus(status.equals("TIME_ENDED") || status.equals("OTHER_ORGANIZATION") ? "PUBLISHED" : status);
      value.setActivityStartAt(status.equals("TIME_ENDED") ? now.minusHours(1) : now.plusHours(1));
      value.setActivityEndAt(status.equals("TIME_ENDED") ? now : now.plusHours(2));
      return value;
    }

    void cleanup(String action) {
      switch (action) {
        case "DECLINE" -> promotions.respond(21L, 300L, new PromotionService.PromotionResponseRequest("DECLINE"));
        case "EXPIRE" -> { offer.setExpiresAt(now); assertThat(expiry.expireOne(300L)).isTrue(); }
        case "CANCEL" -> cancellation.cancel(21L, 100L, new RegistrationCancellationService.CancellationRequest("released"));
        default -> throw new IllegalArgumentException(action);
      }
    }
  }
}
