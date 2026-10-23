package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.*;
import com.volunteerflow.infrastructure.event.BusinessOutboxFixture;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PromotionReconciliationHooksTest {
  @ParameterizedTest
  @ValueSource(strings = {"capacity", "cancellation", "decline", "expiry"})
  void capacityIncreaseCancellationDeclineAndExpiryScheduleThePositionInTheSameTransaction(String action) {
    HookFixture f = new HookFixture(action, false);
    f.action.run();
    assertThat(f.tx.commits).isEqualTo(1);
    assertThat(f.tx.committed).anyMatch(PromotionReconcileTask.class::isInstance);
    PromotionReconcileTask task = f.tx.committed.stream().filter(PromotionReconcileTask.class::isInstance)
        .map(PromotionReconcileTask.class::cast).findFirst().orElseThrow();
    assertThat(task.getPositionId()).isEqualTo(20);
    assertThat(task.getOrganizationId()).isEqualTo(10);
    assertThat(task.getNextRunAt()).isEqualTo(f.now);
    if (action.equals("decline")) assertThat(f.immediateCalls).isEqualTo(1);
    else if (action.equals("capacity")) verify(f.promotions).fillAvailableFirstComeSeats(any(), eq(7L), eq("more seats"));
    else verify(f.promotions).offerNextFirstCome(eq(20L), any(), anyString());
    verifyNoInteractions(f.tx.unavailableKafka);
  }

  @ParameterizedTest
  @ValueSource(strings = {"capacity", "cancellation", "decline", "expiry"})
  void taskInsertFailureRollsBackBusinessAuditAndOutbox(String action) {
    HookFixture f = new HookFixture(action, true);
    assertThatThrownBy(f.action::run).isSameAs(f.failure);
    assertThat(f.tx.committed).isEmpty();
    assertThat(f.tx.staged).isEmpty();
    assertThat(f.tx.rollbacks).isEqualTo(1);
    verifyNoInteractions(f.promotions);
  }

  private static class HookFixture {
    final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 10, 0);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
    final BusinessOutboxFixture tx = new BusinessOutboxFixture(clock);
    final PromotionService promotions = mock(PromotionService.class);
    final RuntimeException failure = new IllegalStateException("task insert failed");
    int immediateCalls;
    final Runnable action;

    HookFixture(String kind, boolean fail) {
      PromotionReconcileTaskMapper tasks = mock(PromotionReconcileTaskMapper.class);
      when(tasks.upsertRequest(10L, 20L, now)).thenAnswer(call -> {
        if (fail) throw failure;
        PromotionReconcileTask row = new PromotionReconcileTask(); row.setPositionId(20L);
        row.setOrganizationId(10L); row.setNextRunAt(now); return tx.stage(row);
      });
      PromotionReconciliationRequester requester = tx.proxy(new PromotionReconciliationRequester(tasks));
      ActivityRegistrationPolicyService policies = mock(ActivityRegistrationPolicyService.class);
      Activity promotionActivity = new Activity();
      promotionActivity.setId(11L); promotionActivity.setOrganizationId(10L);
      promotionActivity.setStatus("PUBLISHED"); promotionActivity.setActivityEndAt(now.plusHours(2));
      when(policies.activityForPromotion(10L, 11L)).thenReturn(promotionActivity);
      OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
      RegistrationMapper registrations = mock(RegistrationMapper.class);
      RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
      PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
      ActivityPosition position = PromotionServiceTest.position();
      RegistrationCycle cycle = PromotionServiceTest.cycle(101L, 100L, 21L);
      Registration registration = PromotionServiceTest.registration(100L, 21L);
      PromotionOffer offer = new PromotionOffer(); offer.setId(300L); offer.setOrganizationId(10L);
      offer.setActivityId(11L); offer.setPositionId(20L); offer.setRegistrationCycleId(101L);
      offer.setStatus("PENDING"); offer.setExpiresAt(now.plusMinutes(30));
      OrganizationMember member = new OrganizationMember(); member.setUserId(21L); member.setOrganizationId(10L);
      when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(member);
      when(members.selectMemberForUpdateAnyStatus(21L, 10L)).thenReturn(member);
      when(policies.lockPositionForRegistrationChange(20L)).thenReturn(position);
      when(policies.cancellationTiming(10L, 11L))
          .thenReturn(new ActivityRegistrationPolicyService.CancellationTiming(null, now.plusHours(1)));
      when(offers.currentDatabaseTime()).thenReturn(now);
      when(registrations.selectOwnedById(100L, 21L)).thenReturn(registration);
      when(cycles.selectActiveOrLatest(100L)).thenReturn(cycle);
      when(cycles.selectActiveCycleForUpdate(100L)).thenReturn(cycle);
      when(cycles.selectById(101L)).thenReturn(cycle);
      when(cycles.selectByIdForUpdate(101L)).thenReturn(cycle);
      when(cycles.updateById(any(RegistrationCycle.class))).thenAnswer(call -> tx.stage(call.getArgument(0)));
      when(cycles.selectFirstWaitlistedForUpdate(20L)).thenAnswer(call -> { immediateCalls++; return null; });
      when(offers.selectById(300L)).thenReturn(offer);
      when(offers.selectByIdForUpdate(300L)).thenReturn(offer);
      when(offers.updateById(any(PromotionOffer.class))).thenAnswer(call -> tx.stage(call.getArgument(0)));
      if (kind.equals("cancellation")) {
        cycle.setStatus("CONFIRMED");
        var service = tx.proxy(new RegistrationCancellationService(policies, members, registrations,
            cycles, offers, promotions, tx.audit, tx.publisher, requester));
        action = () -> service.cancel(21L, 100L, new RegistrationCancellationService.CancellationRequest(null));
      } else if (kind.equals("decline")) {
        var service = tx.proxy(new PromotionService(policies, members, registrations, cycles, offers,
            mock(OrganizationAuthorizationService.class), tx.audit, tx.publisher, requester));
        action = () -> service.respond(21L, 300L, new PromotionService.PromotionResponseRequest("DECLINE"));
      } else if (kind.equals("expiry")) {
        offer.setExpiresAt(now);
        var service = tx.proxy(new PromotionExpiryService(offers, cycles, members, policies, promotions,
            tx.audit, tx.publisher, requester));
        action = () -> service.expireOne(300L);
      } else {
        Activity activity = new Activity(); activity.setId(11L); activity.setOrganizationId(10L);
        activity.setStatus("PUBLISHED"); activity.setActivityStartAt(now.plusDays(1));
        activity.setActivityEndAt(now.plusDays(2));
        ActivityMapper activities = mock(ActivityMapper.class);
        ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
        when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
        when(positions.selectById(20L)).thenReturn(position);
        when(positions.selectByIdForUpdate(20L)).thenReturn(position);
        when(positions.updatePublishedFields(eq(20L), eq(10L), eq(11L), anyMap()))
            .thenAnswer(call -> tx.stage(position));
        var service = tx.proxy(new ActivityChangeService(activities, positions, mock(ActivityQuestionMapper.class),
            mock(ActivityPositionQuestionMapper.class), cycles, mock(OrganizationAuthorizationService.class),
            tx.audit, tx.publisher, promotions, mock(ActivityDetailCacheInvalidator.class), clock, requester));
        action = () -> service.changePosition(7L, 20L, new ActivityChangeService.PositionChangeRequest(
            "position", "description", 21, "FIRST_COME", 30, null, null, null, "more seats"));
      }
    }
  }
}
