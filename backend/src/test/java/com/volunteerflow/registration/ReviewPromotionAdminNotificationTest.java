package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.*;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real business/Spring/Outbox codec with staged mapper storage, not live MySQL or Kafka. */
class ReviewPromotionAdminNotificationTest {
  private static final String ADMIN_EVENT = "registration.promotion-admin-action-required.v1";

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "EXPIRED"})
  void reviewTerminalOfferSnapshotsEveryEligibleAdministratorAndDoesNotAutoSelect(String outcome) {
    Fixture f = new Fixture(outcome, "REVIEW", List.of(31L, 32L, 31L));
    f.transition();
    var events = f.events();
    assertThat(events).hasSize(3);
    assertThat(events.get(0).eventType()).isEqualTo(outcome.equals("DECLINED")
        ? "registration.promotion-declined.v1" : "registration.promotion-expired.v1");
    assertThat(events.stream().filter(event -> event.eventType().equals(ADMIN_EVENT)))
        .extracting(event -> ((NotificationIntentPayload) event.payload()).userId())
        .containsExactly(31L, 32L);
    assertThat(events.stream().filter(event -> event.eventType().equals(ADMIN_EVENT)))
        .allSatisfy(event -> {
          var intent = (NotificationIntentPayload) event.payload();
          assertThat(event.organizationId()).isEqualTo(10L);
          assertThat(event.activityId()).isEqualTo(11L);
          assertThat(intent.notificationType()).isEqualTo("PROMOTION_ADMIN_ACTION_REQUIRED");
          assertThat(intent.businessId()).isEqualTo(300L);
          assertThat(intent.content()).contains(outcome.equals("DECLINED") ? "拒绝" : "过期");
        });
    assertThat(f.outbox.commits).isEqualTo(1);
    verify(f.cycles, never()).selectFirstWaitlistedForUpdate(anyLong());
    verifyNoInteractions(f.automaticPromotions);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "EXPIRED"})
  void firstComeTerminalOfferDoesNotSendReviewAdministratorNotifications(String outcome) {
    Fixture f = new Fixture(outcome, "FIRST_COME", List.of(31L));
    f.transition();
    assertThat(f.events()).hasSize(1).noneMatch(event -> event.eventType().equals(ADMIN_EVENT));
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "EXPIRED"})
  void noEligibleAdministratorStillCommitsTerminalTransition(String outcome) {
    Fixture f = new Fixture(outcome, "REVIEW", List.of());
    f.transition();
    assertThat(f.events()).hasSize(1);
    assertThat(f.offer.getStatus()).isEqualTo(outcome);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "EXPIRED"})
  void duplicateProcessingDoesNotRepublishAdminIntents(String outcome) {
    Fixture f = new Fixture(outcome, "REVIEW", List.of(31L, 32L));
    f.transition();
    assertThat(f.events()).hasSize(3);
    f.outbox.afterRollback = () -> {};
    if (outcome.equals("DECLINED")) {
      assertThatThrownBy(f::transition).isInstanceOf(com.volunteerflow.infrastructure.web.BusinessException.class);
    } else {
      assertThat(f.expiry.expireOne(300L)).isFalse();
    }
    assertThat(f.events()).hasSize(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DECLINED", "EXPIRED"})
  void partialAdministratorOutboxFailureRollsBackBusinessAuditAndEveryEventThenCanRetry(String outcome) {
    Fixture f = new Fixture(outcome, "REVIEW", List.of(31L, 32L));
    f.outbox.failAtInsert = 3; // Original fact/candidate intent and first admin event were staged.
    assertThatThrownBy(f::transition).isSameAs(f.outbox.failure);
    assertThat(f.outbox.committed).isEmpty();
    assertThat(f.outbox.staged).isEmpty();
    assertThat(f.outbox.rollbacks).isEqualTo(1);
    assertThat(f.offer.getStatus()).isEqualTo("PENDING");
    assertThat(f.cycle.getStatus()).isEqualTo("WAITLISTED");
    f.transition();
    assertThat(f.events()).hasSize(3);
    assertThat(f.offer.getStatus()).isEqualTo(outcome);
    verifyNoInteractions(f.outbox.unavailableKafka);
  }

  private static final class Fixture {
    final String outcome;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC);
    final LocalDateTime now = LocalDateTime.now(clock);
    final BusinessOutboxFixture outbox = new BusinessOutboxFixture(clock);
    final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
    final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
    final ActivityRegistrationPolicyService policies = mock(ActivityRegistrationPolicyService.class);
    final PromotionService automaticPromotions = mock(PromotionService.class);
    final PromotionOffer offer = new PromotionOffer();
    final RegistrationCycle cycle = PromotionServiceTest.cycle(101L, 100L, 21L);
    final PromotionService responses;
    final PromotionExpiryService expiry;

    Fixture(String outcome, String mode, List<Long> administratorIds) {
      this.outcome = outcome;
      OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
      when(members.selectNotificationRecipientIds(10L, "registration:promote"))
          .thenReturn(administratorIds);
      OrganizationMember member = new OrganizationMember();
      member.setOrganizationId(10L); member.setUserId(21L); member.setStatus("ACTIVE");
      when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(member);
      when(members.selectMemberForUpdateAnyStatus(21L, 10L)).thenReturn(member);
      ActivityPosition position = PromotionServiceTest.position();
      position.setRegistrationMode(mode);
      Activity activity = new Activity();
      activity.setId(11L); activity.setOrganizationId(10L); activity.setStatus("PUBLISHED");
      activity.setActivityEndAt(now.plusHours(1));
      when(policies.lockActivityForRegistrationChange(10L, 11L)).thenReturn(activity);
      when(policies.activityForPromotion(10L, 11L)).thenReturn(activity);
      when(policies.lockPositionForRegistrationChange(20L)).thenReturn(position);
      offer.setId(300L); offer.setRegistrationCycleId(101L); offer.setOrganizationId(10L);
      offer.setActivityId(11L); offer.setPositionId(20L); offer.setStatus("PENDING");
      offer.setExpiresAt(outcome.equals("EXPIRED") ? now : now.plusMinutes(30));
      when(offers.selectById(300L)).thenReturn(offer);
      when(offers.selectByIdForUpdate(300L)).thenReturn(offer);
      when(offers.currentDatabaseTime()).thenReturn(now);
      when(cycles.selectById(101L)).thenReturn(cycle);
      when(cycles.selectByIdForUpdate(101L)).thenReturn(cycle);
      when(offers.updateById(any(PromotionOffer.class))).thenAnswer(call -> outbox.stage(call.getArgument(0)));
      when(cycles.updateById(any(RegistrationCycle.class))).thenAnswer(call -> outbox.stage(call.getArgument(0)));
      outbox.afterRollback = () -> {
        offer.setStatus("PENDING"); offer.setRespondedAt(null);
        cycle.setStatus("WAITLISTED"); cycle.setAttendanceStatus(null);
      };
      responses = outbox.proxy(new PromotionService(policies, members, mock(RegistrationMapper.class),
          cycles, offers, mock(OrganizationAuthorizationService.class), outbox.audit, outbox.publisher,
          mock(PromotionReconciliationRequester.class)));
      expiry = outbox.proxy(new PromotionExpiryService(offers, cycles, members, policies,
          automaticPromotions, outbox.audit, outbox.publisher, mock(PromotionReconciliationRequester.class)));
    }

    void transition() {
      if (outcome.equals("DECLINED")) responses.respond(21L, 300L, new PromotionService.PromotionResponseRequest("DECLINE"));
      else assertThat(expiry.expireOne(300L)).isTrue();
    }

    List<DomainEventEnvelope> events() {
      return outbox.rows().stream().map(row -> outbox.codec.decode(row.getPayloadJson())).toList();
    }
  }
}
