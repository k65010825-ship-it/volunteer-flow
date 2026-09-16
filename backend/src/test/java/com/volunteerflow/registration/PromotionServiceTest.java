package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.PromotionService.PromotionRequest;
import com.volunteerflow.registration.PromotionService.PromotionResponseRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class PromotionServiceTest {
  final ActivityRegistrationPolicyService policies = mock(ActivityRegistrationPolicyService.class);
  final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  final RegistrationMapper registrations = mock(RegistrationMapper.class);
  final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
  final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  final AuditService audit = mock(AuditService.class);
  final PromotionService service = new PromotionService(
      policies, members, registrations, cycles, offers, authorization, audit);
  final LocalDateTime now = LocalDateTime.of(2026, 9, 16, 8, 0);
  Registration registration;
  RegistrationCycle cycle;
  ActivityPosition position;
  PromotionOffer offer;

  @BeforeEach
  void arrange() {
    registration = registration(100L, 21L);
    cycle = cycle(101L, 100L, 21L);
    position = position();
    offer = offer(300L, 101L);
    when(registrations.selectById(100L)).thenReturn(registration);
    when(cycles.selectActiveOrLatest(100L)).thenReturn(cycle);
    when(cycles.selectById(101L)).thenReturn(cycle);
    when(cycles.selectByIdForUpdate(101L)).thenReturn(cycle);
    when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(cycle);
    when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(new OrganizationMember());
    when(policies.lockPositionForRegistrationChange(20L)).thenReturn(position);
    when(offers.selectById(300L)).thenReturn(offer);
    when(offers.selectByIdForUpdate(300L)).thenReturn(offer);
    when(offers.currentDatabaseTime()).thenReturn(now);
    when(offers.insert(any(PromotionOffer.class))).thenReturn(1);
    when(offers.updateById(any(PromotionOffer.class))).thenReturn(1);
    when(cycles.updateById(any(RegistrationCycle.class))).thenReturn(1);
  }

  @Test
  void offersExactlyOneQueueHeadUsingDatabaseTime() {
    when(cycles.selectFirstWaitlistedForUpdate(20L)).thenReturn(cycle);
    PromotionOffer result = service.offerNextFirstCome(20L, 7L, "Released seat");
    assertThat(result.getRegistrationCycleId()).isEqualTo(101L);
    assertThat(result.getStatus()).isEqualTo("PENDING");
    assertThat(result.getExpiresAt()).isEqualTo(now.plusMinutes(30));
    assertThat(cycle.getStatus()).isEqualTo("WAITLISTED");
    verify(offers, times(1)).insert(result);
    verify(audit).record(10L, 7L, "promotion.offered", "promotion_offer", result.getId(), "Released seat");
    verifyNoInteractions(members);
  }

  @Test
  void fullCapacityIncludesUnexpiredPendingReservations() {
    when(cycles.countConfirmed(20L)).thenReturn(19L);
    when(offers.countActiveReservations(20L)).thenReturn(1L);
    assertThat(service.offerNextFirstCome(20L, 7L, null)).isNull();
    verify(cycles, never()).selectFirstWaitlistedForUpdate(anyLong());
    verify(offers, never()).insert(any(PromotionOffer.class));
  }

  @Test
  void emptyQueueAndReviewPositionDoNotCreateAutomaticOffers() {
    assertThat(service.offerNextFirstCome(20L, 7L, null)).isNull();
    position.setRegistrationMode("REVIEW");
    assertThat(service.offerNextFirstCome(20L, 7L, null)).isNull();
    verify(offers, never()).insert(any(PromotionOffer.class));
  }

  @Test
  void manualOfferRequiresPermissionAndUnorderedReviewCandidate() {
    position.setRegistrationMode("REVIEW");
    cycle.setWaitlistSequence(null);
    PromotionOffer result = service.createManualOffer(7L, 100L, new PromotionRequest("  Selected  "));
    assertThat(result.getReason()).isEqualTo("Selected");
    assertThat(result.getCreatedBy()).isEqualTo(7L);
    assertThat(result.getRegistrationCycleId()).isEqualTo(101L);
    var order = inOrder(authorization, members, policies, cycles, offers);
    order.verify(authorization).requirePermission(7L, 10L, "registration:promote");
    order.verify(members).selectActiveMemberForUpdate(21L, 10L);
    order.verify(policies).lockPositionForRegistrationChange(20L);
    order.verify(cycles).selectActiveCycleForUpdate(100L);
    order.verify(offers).selectByCycleForUpdate(101L);
  }

  @Test
  void manualOfferRejectsWrongModeSequenceAndFullCapacity() {
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "POSITION_NOT_REVIEW_MODE");
    position.setRegistrationMode("REVIEW");
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "REGISTRATION_NOT_WAITLISTED");
    cycle.setWaitlistSequence(null);
    when(cycles.countConfirmed(20L)).thenReturn(20L);
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "POSITION_CAPACITY_FULL");
    verify(offers, never()).insert(any(PromotionOffer.class));
  }

  @Test
  void existingOfferAndUniqueConstraintCompetitionAreStableConflicts() {
    position.setRegistrationMode("REVIEW");
    cycle.setWaitlistSequence(null);
    when(offers.selectByCycleForUpdate(101L)).thenReturn(offer);
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "PROMOTION_OFFER_CONFLICT");
    when(offers.selectByCycleForUpdate(101L)).thenReturn(null);
    when(offers.insert(any(PromotionOffer.class))).thenThrow(new DuplicateKeyException("duplicate"));
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "PROMOTION_OFFER_CONFLICT");
    verifyNoInteractions(audit);
  }

  @Test
  void unrelatedPersistenceFailuresAreNotHiddenAsConflicts() {
    when(cycles.selectFirstWaitlistedForUpdate(20L)).thenReturn(cycle);
    DataIntegrityViolationException failure = new DataIntegrityViolationException("database failure");
    when(offers.insert(any(PromotionOffer.class))).thenThrow(failure);
    assertThatThrownBy(() -> service.offerNextFirstCome(20L, 7L, null)).isSameAs(failure);
  }

  @Test
  void acceptConfirmsCycleAndOfferAtomicallyInRequiredLockOrder() {
    PromotionOffer result = service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT"));
    assertThat(result.getStatus()).isEqualTo("ACCEPTED");
    assertThat(result.getRespondedAt()).isEqualTo(now);
    assertThat(cycle.getStatus()).isEqualTo("CONFIRMED");
    var order = inOrder(members, policies, cycles, offers);
    order.verify(members).selectActiveMemberForUpdate(21L, 10L);
    order.verify(policies).lockPositionForRegistrationChange(20L);
    order.verify(cycles).selectByIdForUpdate(101L);
    order.verify(offers).selectByIdForUpdate(300L);
    verify(cycles).updateById(cycle);
    verify(offers).updateById(offer);
    verify(audit).record(10L, 21L, "promotion.accepted", "promotion_offer", 300L, "CONFIRMED");
  }

  @Test
  void declineAdvancesFirstComeQueueButNotReviewPool() {
    RegistrationCycle next = cycle(102L, 110L, 22L);
    next.setWaitlistSequence(2L);
    when(cycles.selectFirstWaitlistedForUpdate(20L)).thenReturn(next);
    service.respond(21L, 300L, new PromotionResponseRequest("DECLINE"));
    assertThat(offer.getStatus()).isEqualTo("DECLINED");
    assertThat(cycle.getStatus()).isEqualTo("PROMOTION_DECLINED");
    ArgumentCaptor<PromotionOffer> inserted = ArgumentCaptor.forClass(PromotionOffer.class);
    verify(offers).insert(inserted.capture());
    assertThat(inserted.getValue().getRegistrationCycleId()).isEqualTo(102L);
  }

  @Test
  void reviewDeclineDoesNotAutomaticallyPickCandidate() {
    position.setRegistrationMode("REVIEW");
    service.respond(21L, 300L, new PromotionResponseRequest("DECLINE"));
    assertThat(cycle.getStatus()).isEqualTo("PROMOTION_DECLINED");
    verify(cycles, never()).selectFirstWaitlistedForUpdate(anyLong());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACCEPT", "DECLINE"})
  void rejectsOfferAtExactExpiryWithoutMutation(String decision) {
    offer.setExpiresAt(now);
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest(decision)), "PROMOTION_OFFER_EXPIRED");
    assertThat(cycle.getStatus()).isEqualTo("WAITLISTED");
    verify(offers, never()).updateById(any(PromotionOffer.class));
    verifyNoInteractions(audit);
  }

  @Test
  void repeatedResponseAndTerminalCycleAreRejected() {
    offer.setStatus("ACCEPTED");
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT")), "PROMOTION_OFFER_NOT_PENDING");
    offer.setStatus("PENDING");
    cycle.setStatus("CANCELED");
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT")), "REGISTRATION_NOT_WAITLISTED");
    verifyNoInteractions(audit);
  }

  @Test
  void foreignOwnerAndChangedLockedOfferAreHidden() {
    assertThatThrownBy(() -> service.respond(99L, 300L, new PromotionResponseRequest("ACCEPT")))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    PromotionOffer changed = offer(300L, 999L);
    when(offers.selectByIdForUpdate(300L)).thenReturn(changed);
    assertThatThrownBy(() -> service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT")))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(audit);
  }

  @Test
  void permissionFailureAndCrossOrganizationPositionPreventMutation() {
    BusinessException denied = new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Denied");
    doThrow(denied).when(authorization).requirePermission(7L, 10L, "registration:promote");
    assertThatThrownBy(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null))).isSameAs(denied);
    verifyNoInteractions(members);
    position.setOrganizationId(99L);
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT")), "PROMOTION_OFFER_NOT_FOUND");
    verifyNoInteractions(audit);
  }

  @Test
  void invalidResponseAndOversizedReasonFailBeforeWrites() {
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest("INVALID")), "INVALID_PROMOTION_RESPONSE");
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest("x".repeat(501))), "INVALID_PROMOTION_REASON");
    verifyNoInteractions(audit);
  }

  @Test
  void changedLockedCycleDoesNotOfferAnotherAttempt() {
    RegistrationCycle changed = cycle(102L, 100L, 21L);
    when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(changed);
    position.setRegistrationMode("REVIEW");
    rejects(() -> service.createManualOffer(7L, 100L, new PromotionRequest(null)), "REGISTRATION_NOT_WAITLISTED");
    verify(offers, never()).insert(any(PromotionOffer.class));
  }

  @Test
  void failedStatusWriteAndAuditFailurePropagateForTransactionRollback() {
    when(offers.updateById(offer)).thenReturn(0);
    rejects(() -> service.respond(21L, 300L, new PromotionResponseRequest("ACCEPT")), "PROMOTION_OFFER_CONFLICT");
    verifyNoInteractions(audit);
  }

  static Registration registration(Long id, Long userId) {
    Registration value = new Registration();
    value.setId(id); value.setOrganizationId(10L); value.setActivityId(11L); value.setUserId(userId);
    return value;
  }

  static RegistrationCycle cycle(Long id, Long registrationId, Long userId) {
    RegistrationCycle value = new RegistrationCycle();
    value.setId(id); value.setRegistrationId(registrationId); value.setOrganizationId(10L);
    value.setActivityId(11L); value.setPositionId(20L); value.setUserId(userId);
    value.setStatus("WAITLISTED"); value.setWaitlistSequence(1L); value.setCycleNumber(1);
    return value;
  }

  static ActivityPosition position() {
    ActivityPosition value = new ActivityPosition();
    value.setId(20L); value.setOrganizationId(10L); value.setActivityId(11L);
    value.setRegistrationMode("FIRST_COME"); value.setStatus("ACTIVE");
    value.setCapacity(20); value.setPromotionTimeoutMinutes(30);
    return value;
  }

  PromotionOffer offer(Long id, Long cycleId) {
    PromotionOffer value = new PromotionOffer();
    value.setId(id); value.setRegistrationCycleId(cycleId); value.setOrganizationId(10L);
    value.setActivityId(11L); value.setPositionId(20L); value.setStatus("PENDING");
    value.setExpiresAt(now.plusMinutes(30)); value.setVersion(0);
    return value;
  }

  static void rejects(Runnable action, String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        error -> assertThat(error.code()).isEqualTo(code));
  }
}
