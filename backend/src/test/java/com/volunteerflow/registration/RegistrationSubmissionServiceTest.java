package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.QuestionDefinition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.RegistrationForm;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.RegistrationPolicy;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import com.volunteerflow.registration.RegistrationSubmissionService.RegistrationResult;
import com.volunteerflow.registration.RegistrationSubmissionService.SubmitRegistrationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class RegistrationSubmissionServiceTest {
  private final ActivityRegistrationPolicyService policies =
      mock(ActivityRegistrationPolicyService.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final RegistrationMapper registrations = mock(RegistrationMapper.class);
  private final RegistrationCycleMapper cycleMapper = mock(RegistrationCycleMapper.class);
  private final RegistrationAnswerMapper answers = mock(RegistrationAnswerMapper.class);
  private final PromotionOfferMapper offerMapper = mock(PromotionOfferMapper.class);
  private final AuditService audit = mock(AuditService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC);
  private final RegistrationSubmissionService service =
      new RegistrationSubmissionService(
          policies,
          members,
          registrations,
          cycleMapper,
          answers,
          offerMapper,
          new RegistrationAnswerValidator(),
          audit,
          clock);
  private Activity activity;
  private ActivityPosition position;

  @BeforeEach
  void arrangeOpenFirstComePosition() {
    activity = new Activity();
    activity.setId(10L);
    activity.setOrganizationId(100L);
    activity.setStatus("PUBLISHED");
    activity.setRegistrationStartAt(LocalDateTime.now(clock));
    activity.setRegistrationEndAt(LocalDateTime.now(clock).plusHours(1));
    position = new ActivityPosition();
    position.setId(20L);
    position.setOrganizationId(100L);
    position.setActivityId(10L);
    position.setStatus("ACTIVE");
    position.setCapacity(20);
    position.setRegistrationMode("FIRST_COME");
    position.setNextWaitlistSequence(1L);
    when(policies.loadForm(21L, 10L, 20L))
        .thenReturn(new RegistrationForm(activity, position, List.of()));
    when(members.selectActiveMemberForUpdate(21L, 100L)).thenReturn(new OrganizationMember());
    when(policies.lockPolicyForSubmission(21L, 10L, 20L))
        .thenReturn(new RegistrationPolicy(activity, position, List.of()));
    when(policies.allocateNextWaitlistSequence(position))
        .thenAnswer(
            invocation -> {
              long sequence = position.getNextWaitlistSequence();
              position.setNextWaitlistSequence(sequence + 1);
              return sequence;
            });
  }

  @Test
  void firstComeSubmissionConfirmsAndPersistsStableRelationCycleAndAudit() {
    RegistrationResult result = service.submit(21L, 10L, requestFor(20L));
    assertThat(result.status()).isEqualTo("CONFIRMED");
    assertThat(result.waitlistSequence()).isNull();
    assertThat(result.currentPosition()).isNull();
    ArgumentCaptor<Registration> relation = ArgumentCaptor.forClass(Registration.class);
    verify(registrations).insert(relation.capture());
    assertThat(relation.getValue().getId()).isEqualTo(result.registrationId()).isNotNull();
    assertThat(relation.getValue().getLastCycleNumber()).isEqualTo(1);
    assertThat(relation.getValue().getUserId()).isEqualTo(21L);
    assertThat(relation.getValue().getOrganizationId()).isEqualTo(100L);
    assertThat(relation.getValue().getActivityId()).isEqualTo(10L);
    RegistrationCycle cycle = insertedCycle();
    assertThat(cycle.getId()).isEqualTo(result.cycleId()).isNotNull();
    assertThat(cycle.getRegistrationId()).isEqualTo(result.registrationId());
    assertThat(cycle.getPositionId()).isEqualTo(20L);
    assertThat(cycle.getCycleNumber()).isEqualTo(1);
    assertThat(cycle.getStatus()).isEqualTo("CONFIRMED");
    assertThat(cycle.getSubmittedAt()).isEqualTo(LocalDateTime.now(clock));
    verify(audit)
        .record(
            100L,
            21L,
            "registration.submitted",
            "registration_cycle",
            result.cycleId(),
            "CONFIRMED");
    var order = inOrder(members, policies, registrations, cycleMapper, audit);
    order.verify(policies).loadForm(21L, 10L, 20L);
    order.verify(members).selectActiveMemberForUpdate(21L, 100L);
    order.verify(policies).lockPolicyForSubmission(21L, 10L, 20L);
    order.verify(registrations).selectByActivityAndUser(10L, 21L);
  }

  @Test
  void firstComeSubmissionJoinsWaitlistWhenPositionIsFull() {
    when(cycleMapper.countConfirmed(20L)).thenReturn(20L);
    when(offerMapper.countActiveReservations(20L)).thenReturn(0L);
    when(cycleMapper.countActiveWaitlistBefore(20L, 1L)).thenReturn(0L);
    RegistrationResult result = service.submit(21L, 10L, requestFor(20L));
    assertThat(result.status()).isEqualTo("WAITLISTED");
    assertThat(result.waitlistSequence()).isEqualTo(1L);
    assertThat(result.currentPosition()).isEqualTo(1);
    assertThat(position.getNextWaitlistSequence()).isEqualTo(2L);
    assertThat(insertedCycle().getWaitlistSequence()).isEqualTo(1L);
  }

  @Test
  void pendingReservationsConsumeCapacityAndRankCountsOnlyEarlierActiveCycles() {
    when(cycleMapper.countConfirmed(20L)).thenReturn(19L);
    when(offerMapper.countActiveReservations(20L)).thenReturn(1L);
    position.setNextWaitlistSequence(8L);
    when(cycleMapper.countActiveWaitlistBefore(20L, 8L)).thenReturn(2L);
    RegistrationResult result = service.submit(21L, 10L, requestFor(20L));
    assertThat(result.status()).isEqualTo("WAITLISTED");
    assertThat(result.waitlistSequence()).isEqualTo(8L);
    assertThat(result.currentPosition()).isEqualTo(3);
  }

  @Test
  void reviewSubmissionWaitsForReviewWithoutReservingCapacityOrAllocatingSequence() {
    position.setRegistrationMode("REVIEW");
    RegistrationResult result = service.submit(21L, 10L, requestFor(20L));
    assertThat(result.status()).isEqualTo("PENDING_REVIEW");
    assertThat(result.waitlistSequence()).isNull();
    assertThat(result.currentPosition()).isNull();
    verifyNoInteractions(offerMapper);
    verify(cycleMapper, never()).countConfirmed(any());
    verify(policies, never()).allocateNextWaitlistSequence(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING_REVIEW", "CONFIRMED", "WAITLISTED"})
  void activeCycleRejectsSecondSubmissionEvenForAnotherPosition(String status) {
    Registration existing = existingRegistration();
    RegistrationCycle active = new RegistrationCycle();
    active.setStatus(status);
    active.setPositionId(99L);
    when(registrations.selectByActivityAndUser(10L, 21L)).thenReturn(existing);
    when(cycleMapper.selectActiveCycleForUpdate(50L)).thenReturn(active);
    rejects(requestFor(20L), "REGISTRATION_ALREADY_ACTIVE");
    verify(cycleMapper, never()).insert(any(RegistrationCycle.class));
    verifyNoInteractions(audit);
  }

  @Test
  void reusesStableRegistrationAndCreatesNextCycleWithoutUpdatingHistory() {
    Registration existing = existingRegistration();
    when(registrations.selectByActivityAndUser(10L, 21L)).thenReturn(existing);
    RegistrationResult result = service.submit(21L, 10L, requestFor(20L));
    assertThat(result.registrationId()).isEqualTo(50L);
    assertThat(insertedCycle().getCycleNumber()).isEqualTo(4);
    assertThat(existing.getLastCycleNumber()).isEqualTo(4);
    verify(registrations).updateById(existing);
    verify(registrations, never()).insert(any(Registration.class));
    verify(cycleMapper, never()).updateById(any(RegistrationCycle.class));
  }

  @Test
  void registrationWindowEndIsExclusive() {
    activity.setRegistrationEndAt(LocalDateTime.now(clock));
    rejects(requestFor(20L), "REGISTRATION_WINDOW_CLOSED");
    verifyNoInteractions(registrations, cycleMapper, answers, audit);
  }

  @Test
  void registrationCannotStartBeforeWindowOrOnUnpublishedActivity() {
    activity.setRegistrationStartAt(LocalDateTime.now(clock).plusSeconds(1));
    rejects(requestFor(20L), "REGISTRATION_WINDOW_CLOSED");
    activity.setRegistrationStartAt(LocalDateTime.now(clock));
    activity.setStatus("CANCELED");
    rejects(requestFor(20L), "ACTIVITY_NOT_PUBLISHED");
  }

  @Test
  void membershipMustStillBeActiveWhenLocked() {
    when(members.selectActiveMemberForUpdate(21L, 100L)).thenReturn(null);
    rejects(requestFor(20L), "ORGANIZATION_NOT_FOUND");
    verify(policies, never()).lockPolicyForSubmission(any(), any(), any());
    verifyNoInteractions(registrations, cycleMapper, audit);
  }

  @Test
  void invalidAnswersAreRejectedBeforeWriting() {
    requireTextAnswer();
    rejects(requestFor(20L), "REQUIRED_ANSWER_MISSING");
    verify(registrations, never()).insert(any(Registration.class));
    verifyNoInteractions(answers, audit);
  }

  @Test
  void persistsSubmittedJsonUnderNewCycleUsingLockedServerDefinitions() throws Exception {
    requireTextAnswer();
    AnswerInput input = new AnswerInput("ACTIVITY", 1L, new ObjectMapper().readTree("\"志愿经验\""));
    RegistrationResult result =
        service.submit(21L, 10L, new SubmitRegistrationRequest(20L, List.of(input)));
    ArgumentCaptor<RegistrationAnswer> snapshot = ArgumentCaptor.forClass(RegistrationAnswer.class);
    verify(answers).insert(snapshot.capture());
    assertThat(snapshot.getValue().getRegistrationCycleId()).isEqualTo(result.cycleId());
    assertThat(snapshot.getValue().getOrganizationId()).isEqualTo(100L);
    assertThat(snapshot.getValue().getQuestionScope()).isEqualTo("ACTIVITY");
    assertThat(snapshot.getValue().getQuestionId()).isEqualTo(1L);
    assertThat(snapshot.getValue().getAnswerJson()).isEqualTo("\"志愿经验\"");
  }

  @Test
  void translatesDuplicateKeyRaceToConflict() {
    doThrow(new DuplicateKeyException("unique activity/user"))
        .when(registrations)
        .insert(any(Registration.class));
    assertThatThrownBy(() -> service.submit(21L, 10L, requestFor(20L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("REGISTRATION_CONFLICT");
              assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
            });
  }

  @Test
  void rejectsMissingRequestOrPosition() {
    rejects(null, "INVALID_REGISTRATION_REQUEST");
    rejects(new SubmitRegistrationRequest(null, List.of()), "INVALID_REGISTRATION_REQUEST");
    verifyNoInteractions(policies, members);
  }

  private Registration existingRegistration() {
    Registration registration = new Registration();
    registration.setId(50L);
    registration.setOrganizationId(100L);
    registration.setActivityId(10L);
    registration.setUserId(21L);
    registration.setLastCycleNumber(3);
    registration.setVersion(0);
    return registration;
  }

  private void requireTextAnswer() {
    when(policies.lockPolicyForSubmission(21L, 10L, 20L))
        .thenReturn(
            new RegistrationPolicy(
                activity,
                position,
                List.of(new QuestionDefinition("ACTIVITY", 1L, "TEXT", "经验", true, List.of(), 1))));
  }

  private RegistrationCycle insertedCycle() {
    ArgumentCaptor<RegistrationCycle> cycle = ArgumentCaptor.forClass(RegistrationCycle.class);
    verify(cycleMapper).insert(cycle.capture());
    return cycle.getValue();
  }

  private SubmitRegistrationRequest requestFor(Long positionId) {
    return new SubmitRegistrationRequest(positionId, List.of());
  }

  private void rejects(SubmitRegistrationRequest request, String code) {
    assertThatThrownBy(() -> service.submit(21L, 10L, request))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo(code);
  }
}
