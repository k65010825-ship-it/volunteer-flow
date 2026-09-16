package com.volunteerflow.registration;

import static com.volunteerflow.registration.PromotionServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.CancellationTiming;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.registration.RegistrationCancellationService.CancellationRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegistrationCancellationServiceTest {
  final ActivityRegistrationPolicyService policies = mock(ActivityRegistrationPolicyService.class);
  final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  final RegistrationMapper registrations = mock(RegistrationMapper.class);
  final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
  final AuditService audit = mock(AuditService.class);
  final PromotionService promotions = mock(PromotionService.class);
  final RegistrationCancellationService service = new RegistrationCancellationService(
      policies, members, registrations, cycles, offers, promotions, audit);
  final LocalDateTime now = LocalDateTime.of(2026, 9, 16, 8, 0);
  RegistrationCycle cycle;

  @BeforeEach
  void arrange() {
    cycle = cycle(101L, 100L, 21L);
    cycle.setStatus("CONFIRMED");
    when(registrations.selectOwnedById(100L, 21L)).thenReturn(registration(100L, 21L));
    when(cycles.selectActiveOrLatest(100L)).thenReturn(cycle);
    when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(cycle);
    when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(new OrganizationMember());
    when(policies.lockPositionForRegistrationChange(20L)).thenReturn(position());
    when(policies.cancellationTiming(10L, 11L)).thenReturn(new CancellationTiming(null, now));
    when(offers.currentDatabaseTime()).thenReturn(now);
    when(cycles.updateById(any(RegistrationCycle.class))).thenReturn(1);
    when(offers.updateById(any(PromotionOffer.class))).thenReturn(1);
  }

  @Test
  void nullDeadlineFallsBackToActivityStartAndExactDeadlineRemainsFree() {
    RegistrationCycle result = service.cancel(21L, 100L, new CancellationRequest(null));
    assertThat(result.getStatus()).isEqualTo("CANCELED");
    assertThat(result.getCanceledAt()).isEqualTo(now);
    verify(promotions).offerNextFirstCome(20L, 21L, "Registration canceled");
    verify(audit).record(10L, 21L, "registration.canceled", "registration_cycle", 101L, null);
  }

  @Test
  void explicitDeadlineOverridesLaterActivityStartAndRequiresTrimmedReason() {
    when(policies.cancellationTiming(10L, 11L))
        .thenReturn(new CancellationTiming(now.minusSeconds(1), now.plusDays(1)));
    RegistrationCycle result = service.cancel(21L, 100L, new CancellationRequest("  Sick  "));
    assertThat(result.getStatus()).isEqualTo("LATE_CANCELED");
    assertThat(result.getCancelReason()).isEqualTo("Sick");
    verify(audit)
        .record(
            10L, 21L, "registration.late_canceled", "registration_cycle", 101L, "Sick");
  }

  @Test
  void missingBlankAndOversizedLateReasonsDoNotMutate() {
    when(policies.cancellationTiming(10L, 11L))
        .thenReturn(new CancellationTiming(null, now.minusSeconds(1)));
    rejects(
        () -> service.cancel(21L, 100L, new CancellationRequest(null)),
        "CANCELLATION_REASON_REQUIRED");
    rejects(
        () -> service.cancel(21L, 100L, new CancellationRequest(" \t ")),
        "CANCELLATION_REASON_REQUIRED");
    rejects(
        () -> service.cancel(21L, 100L, new CancellationRequest("x".repeat(501))),
        "INVALID_CANCELLATION_REASON");
    assertThat(cycle.getStatus()).isEqualTo("CONFIRMED");
    verifyNoInteractions(audit, promotions);
  }

  @Test
  void pendingOfferIsCanceledBeforeReleasedReservationIsPromoted() {
    cycle.setStatus("WAITLISTED");
    PromotionOffer offer = new PromotionOffer();
    offer.setId(300L);
    offer.setOrganizationId(10L);
    offer.setActivityId(11L);
    offer.setPositionId(20L);
    offer.setRegistrationCycleId(101L);
    offer.setStatus("PENDING");
    offer.setExpiresAt(now.plusMinutes(10));
    when(offers.selectByCycleForUpdate(101L)).thenReturn(offer);
    service.cancel(21L, 100L, new CancellationRequest(null));
    assertThat(offer.getStatus()).isEqualTo("CANCELED");
    assertThat(offer.getRespondedAt()).isEqualTo(now);
    var order = inOrder(members, policies, cycles, offers, promotions);
    order.verify(members).selectActiveMemberForUpdate(21L, 10L);
    order.verify(policies).lockPositionForRegistrationChange(20L);
    order.verify(cycles).selectActiveCycleForUpdate(100L);
    order.verify(offers).selectByCycleForUpdate(101L);
    order.verify(offers).updateById(offer);
    order.verify(promotions).offerNextFirstCome(20L, 21L, "Registration canceled");
  }

  @Test
  void cancelingExpiredPendingOfferStillAdvancesFirstComeQueue() {
    cycle.setStatus("WAITLISTED");
    PromotionOffer offer = new PromotionOffer();
    offer.setId(300L);
    offer.setOrganizationId(10L);
    offer.setActivityId(11L);
    offer.setPositionId(20L);
    offer.setRegistrationCycleId(101L);
    offer.setStatus("PENDING");
    offer.setExpiresAt(now.minusSeconds(1));
    when(offers.selectByCycleForUpdate(101L)).thenReturn(offer);

    service.cancel(21L, 100L, new CancellationRequest(null));

    assertThat(offer.getStatus()).isEqualTo("CANCELED");
    verify(promotions).offerNextFirstCome(20L, 21L, "Registration canceled");
  }

  @ParameterizedTest
  @ValueSource(strings = {"WAITLISTED", "PENDING_REVIEW"})
  void cancelWithoutOccupiedSeatDoesNotAdvanceQueue(String status) {
    cycle.setStatus(status);
    service.cancel(21L, 100L, new CancellationRequest(null));
    assertThat(cycle.getStatus()).isEqualTo("CANCELED");
    verifyNoInteractions(promotions);
  }

  @Test
  void reviewCancellationNeverAutomaticallySelectsCandidate() {
    var review = position();
    review.setRegistrationMode("REVIEW");
    when(policies.lockPositionForRegistrationChange(20L)).thenReturn(review);
    service.cancel(21L, 100L, new CancellationRequest(null));
    verifyNoInteractions(promotions);
  }

  @Test
  void repeatedForeignAndChangedCycleCancellationCannotMutate() {
    rejects(
        () -> service.cancel(99L, 100L, new CancellationRequest(null)),
        "REGISTRATION_NOT_FOUND");
    when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(null);
    rejects(
        () -> service.cancel(21L, 100L, new CancellationRequest(null)),
        "REGISTRATION_NOT_ACTIVE");
    when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(cycle(102L, 100L, 21L));
    rejects(
        () -> service.cancel(21L, 100L, new CancellationRequest(null)),
        "REGISTRATION_NOT_ACTIVE");
    verifyNoInteractions(audit, promotions);
  }

  @Test
  void auditFailureIsNotSwallowed() {
    IllegalStateException failure = new IllegalStateException("Audit unavailable");
    doThrow(failure)
        .when(audit)
        .record(anyLong(), anyLong(), anyString(), anyString(), anyLong(), isNull());
    assertThatThrownBy(() -> service.cancel(21L, 100L, new CancellationRequest(null)))
        .isSameAs(failure);
  }
}
