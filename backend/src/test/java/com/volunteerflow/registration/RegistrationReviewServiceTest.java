package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityPositionMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationReviewService.ReviewDecisionRequest;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class RegistrationReviewServiceTest {
  private final RegistrationMapper registrations = mock(RegistrationMapper.class);
  private final RegistrationCycleMapper cycleMapper = mock(RegistrationCycleMapper.class);
  private final ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final PromotionOfferMapper offerMapper = mock(PromotionOfferMapper.class);
  private final OrganizationAuthorizationService authorization =
      mock(OrganizationAuthorizationService.class);
  private final AuditService audit = mock(AuditService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC);
  private final RegistrationReviewService service =
      new RegistrationReviewService(
          registrations, cycleMapper, positions, members, offerMapper, authorization, audit, clock);

  private Registration registration;
  private RegistrationCycle snapshot;
  private RegistrationCycle lockedCycle;
  private ActivityPosition lockedPosition;

  @BeforeEach
  void arrangePendingReview() {
    registration = registration();
    snapshot = cycle("PENDING_REVIEW");
    lockedCycle = cycle("PENDING_REVIEW");
    lockedPosition = position("REVIEW");

    when(registrations.selectById(100L)).thenReturn(registration);
    when(cycleMapper.selectActiveOrLatest(100L)).thenReturn(snapshot);
    when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(new OrganizationMember());
    when(positions.selectByIdForUpdate(20L)).thenReturn(lockedPosition);
    when(cycleMapper.selectActiveCycleForUpdate(100L)).thenReturn(lockedCycle);
  }

  @Test
  void confirmRequiresAvailableCapacityIncludingActiveOffers() {
    when(cycleMapper.countConfirmed(20L)).thenReturn(19L);
    when(offerMapper.countActiveReservations(20L)).thenReturn(1L);

    rejects(decision("CONFIRM"), "POSITION_CAPACITY_FULL", HttpStatus.CONFLICT);

    assertThat(lockedCycle.getStatus()).isEqualTo("PENDING_REVIEW");
    verify(cycleMapper, never()).updateById(lockedCycle);
    verifyNoInteractions(audit);
  }

  @ParameterizedTest
  @CsvSource({"CONFIRM,CONFIRMED", "WAITLIST,WAITLISTED", "REJECT,REJECTED"})
  void appliesEveryAllowedDecisionAndRecordsReview(String decision, String resultingStatus) {
    when(cycleMapper.countConfirmed(20L)).thenReturn(18L);
    when(offerMapper.countActiveReservations(20L)).thenReturn(1L);

    RegistrationCycle result = service.decide(7L, 100L, decision(decision));

    assertThat(result).isSameAs(lockedCycle);
    assertThat(result.getStatus()).isEqualTo(resultingStatus);
    assertThat(result.getWaitlistSequence()).isNull();
    assertThat(result.getReviewedBy()).isEqualTo(7L);
    assertThat(result.getReviewedAt()).isEqualTo(LocalDateTime.now(clock));
    assertThat(result.getReviewReason()).isEqualTo("Strong fit");
    verify(cycleMapper).updateById(lockedCycle);
    verify(audit)
        .record(10L, 7L, "registration.reviewed", "registration_cycle", 101L, resultingStatus);
  }

  @ParameterizedTest
  @MethodSource("invalidReviewReasons")
  void rejectsMissingOrBlankReviewReasonBeforeMutation(String decision, String reason) {
    rejects(
        new ReviewDecisionRequest(decision, reason),
        "INVALID_REVIEW_REASON",
        HttpStatus.UNPROCESSABLE_ENTITY);

    verifyNoInteractions(
        registrations, cycleMapper, positions, members, offerMapper, authorization, audit);
  }

  @Test
  void acceptsExactlyFiveHundredCharactersAndPersistsTheTrimmedReason() {
    String reason = "x".repeat(500);

    RegistrationCycle result =
        service.decide(7L, 100L, new ReviewDecisionRequest("REJECT", reason));

    assertThat(result.getReviewReason()).isEqualTo(reason);
    verify(cycleMapper).updateById(lockedCycle);
    verify(audit)
        .record(10L, 7L, "registration.reviewed", "registration_cycle", 101L, "REJECTED");
  }

  @Test
  void acceptsRawReasonLongerThanFiveHundredCharactersWhenTrimmedReasonIsFiveHundred() {
    String trimmedReason = "x".repeat(500);
    String rawReason = " " + trimmedReason + " ";

    RegistrationCycle result =
        service.decide(7L, 100L, new ReviewDecisionRequest("WAITLIST", rawReason));

    assertThat(result.getReviewReason()).isEqualTo(trimmedReason);
  }

  @Test
  void leavesReviewReasonLengthValidationToTheService() throws NoSuchMethodException {
    assertThat(ReviewDecisionRequest.class.getDeclaredMethod("reason").getAnnotation(Size.class))
        .isNull();
  }

  @Test
  void rejectsReviewReasonLongerThanFiveHundredCharactersBeforeMutation() {
    rejects(
        new ReviewDecisionRequest("WAITLIST", "x".repeat(501)),
        "INVALID_REVIEW_REASON",
        HttpStatus.UNPROCESSABLE_ENTITY);

    verifyNoInteractions(
        registrations, cycleMapper, positions, members, offerMapper, authorization, audit);
  }

  @Test
  void persistsTrimmedReviewReason() {
    RegistrationCycle result =
        service.decide(7L, 100L, new ReviewDecisionRequest("CONFIRM", "  Good fit  "));

    assertThat(result.getReviewReason()).isEqualTo("Good fit");
  }

  @Test
  void waitlistDecisionCreatesUnorderedCandidateWithoutCapacityReads() {
    RegistrationCycle result = service.decide(7L, 100L, decision("WAITLIST"));

    assertThat(result.getStatus()).isEqualTo("WAITLISTED");
    assertThat(result.getWaitlistSequence()).isNull();
    verify(cycleMapper, never()).countConfirmed(20L);
    verifyNoInteractions(offerMapper);
  }

  @Test
  void repeatedReviewIsRejectedAfterCycleLock() {
    lockedCycle.setStatus("CONFIRMED");

    rejects(decision("REJECT"), "REGISTRATION_NOT_PENDING_REVIEW", HttpStatus.CONFLICT);

    verify(cycleMapper, never()).updateById(lockedCycle);
    verifyNoInteractions(audit);
  }

  @Test
  void onlyReviewPositionsAcceptDecisions() {
    lockedPosition.setRegistrationMode("FIRST_COME");

    rejects(decision("CONFIRM"), "POSITION_NOT_REVIEW_MODE", HttpStatus.CONFLICT);

    verify(cycleMapper, never()).selectActiveCycleForUpdate(anyLong());
    verifyNoInteractions(audit);
  }

  @Test
  void requiresReviewPermissionBeforeTakingBusinessLocks() {
    BusinessException forbidden =
        new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Forbidden");
    doThrow(forbidden).when(authorization).requirePermission(7L, 10L, "registration:review");

    assertThatThrownBy(() -> service.decide(7L, 100L, decision("CONFIRM"))).isSameAs(forbidden);

    verifyNoInteractions(members, positions, offerMapper, audit);
    verify(cycleMapper, never()).selectActiveCycleForUpdate(anyLong());
  }

  @Test
  void crossOrganizationResourceRemainsHidden() {
    BusinessException hidden =
        new BusinessException(
            HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization was not found");
    doThrow(hidden).when(authorization).requirePermission(7L, 10L, "registration:review");

    assertThatThrownBy(() -> service.decide(7L, 100L, decision("WAITLIST"))).isSameAs(hidden);

    verifyNoInteractions(members, positions, offerMapper, audit);
  }

  @Test
  void locksTargetMemberThenPositionThenCycle() {
    service.decide(7L, 100L, decision("REJECT"));

    var order = inOrder(authorization, members, positions, cycleMapper);
    order.verify(authorization).requirePermission(7L, 10L, "registration:review");
    order.verify(members).selectActiveMemberForUpdate(21L, 10L);
    order.verify(positions).selectByIdForUpdate(20L);
    order.verify(cycleMapper).selectActiveCycleForUpdate(100L);
  }

  @Test
  void revalidatesLockedResourcesAgainstThePrelockSnapshot() {
    lockedPosition.setOrganizationId(99L);

    rejects(decision("REJECT"), "REGISTRATION_NOT_FOUND", HttpStatus.NOT_FOUND);

    verify(cycleMapper, never()).selectActiveCycleForUpdate(anyLong());
    verifyNoInteractions(audit);
  }

  @Test
  void rejectsAChangedCycleAfterWaitingForLocks() {
    lockedCycle.setId(999L);

    rejects(decision("REJECT"), "REGISTRATION_NOT_PENDING_REVIEW", HttpStatus.CONFLICT);

    verify(cycleMapper, never()).updateById(lockedCycle);
    verifyNoInteractions(audit);
  }

  @Test
  void rejectsMissingRegistrationAndUnsupportedDecisionWithoutLocks() {
    rejects(
        new ReviewDecisionRequest("APPROVE", "No"),
        "INVALID_REVIEW_DECISION",
        HttpStatus.UNPROCESSABLE_ENTITY);
    when(registrations.selectById(100L)).thenReturn(null);
    rejects(decision("REJECT"), "REGISTRATION_NOT_FOUND", HttpStatus.NOT_FOUND);

    verifyNoInteractions(members, positions, offerMapper, audit);
  }

  private void rejects(ReviewDecisionRequest request, String code, HttpStatus status) {
    assertThatThrownBy(() -> service.decide(7L, 100L, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(code);
              assertThat(error.status()).isEqualTo(status);
            });
  }

  private ReviewDecisionRequest decision(String decision) {
    return new ReviewDecisionRequest(decision, " Strong fit ");
  }

  private static Stream<Arguments> invalidReviewReasons() {
    return Stream.of(
        Arguments.of("CONFIRM", null),
        Arguments.of("WAITLIST", "   "),
        Arguments.of("REJECT", "\t"));
  }

  private Registration registration() {
    Registration value = new Registration();
    value.setId(100L);
    value.setOrganizationId(10L);
    value.setActivityId(11L);
    value.setUserId(21L);
    return value;
  }

  private RegistrationCycle cycle(String status) {
    RegistrationCycle value = new RegistrationCycle();
    value.setId(101L);
    value.setOrganizationId(10L);
    value.setRegistrationId(100L);
    value.setActivityId(11L);
    value.setPositionId(20L);
    value.setUserId(21L);
    value.setCycleNumber(1);
    value.setStatus(status);
    return value;
  }

  private ActivityPosition position(String mode) {
    ActivityPosition value = new ActivityPosition();
    value.setId(20L);
    value.setOrganizationId(10L);
    value.setActivityId(11L);
    value.setCapacity(20);
    value.setRegistrationMode(mode);
    value.setStatus("ACTIVE");
    return value;
  }
}
