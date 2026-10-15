package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.*;
import com.volunteerflow.audit.*;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationWriter;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Real service/policy/audit/Spring advice; bounded test-only row locks, not a MySQL race claim. */
class ActivityConfirmationFinalizationTest {
  enum Path { REVIEW, PROMOTION, SUBMISSION }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void confirmationAfterCommittedFinalizationIsRejectedWithoutSideEffects(Path path) {
    Fixture f = new Fixture(path);
    f.clock.at(f.end);
    assertThat(f.finalizer.finalizeOne(11L, f.end)).isTrue();
    assertThatThrownBy(f::confirm).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo("ACTIVITY_NOT_OPEN_FOR_CONFIRMATION");
    f.assertFinalInvariant();
    assertThat(f.cycle.getStatus()).isEqualTo(path == Path.REVIEW ? "PENDING_REVIEW" : "WAITLISTED");
    assertThat(f.offer.getStatus()).isEqualTo("PENDING");
    assertThat(f.auditRows).extracting(AuditLog::getAction).containsExactly("activity.finished");
    assertThat(f.notifications).isEmpty();
  }

  @ParameterizedTest @CsvSource({"REVIEW,FINISHED", "REVIEW,CANCELED", "REVIEW,DRAFT",
      "PROMOTION,FINISHED", "PROMOTION,CANCELED", "PROMOTION,DRAFT"})
  void nonPublishedActivityCannotGainConfirmedAttendance(Path path, String status) {
    Fixture f = new Fixture(path); f.activity.setStatus(status);
    assertThatThrownBy(f::confirm).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo("ACTIVITY_NOT_OPEN_FOR_CONFIRMATION");
    assertThat(f.cycle.getStatus()).isNotEqualTo("CONFIRMED");
    assertThat(f.auditRows).isEmpty(); assertThat(f.notifications).isEmpty();
  }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void publishedActivityAtExactEndCannotGainConfirmedAttendance(Path path) {
    Fixture f = new Fixture(path); f.clock.at(f.end);
    assertThatThrownBy(f::confirm).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo("ACTIVITY_NOT_OPEN_FOR_CONFIRMATION");
    assertThat(f.cycle.getStatus()).isNotEqualTo("CONFIRMED");
    assertThat(f.auditRows).isEmpty(); assertThat(f.notifications).isEmpty();
  }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void endBoundaryIsResampledAfterWaitingForDownstreamLocks(Path path) {
    Fixture f = new Fixture(path); f.afterPositionLock = () -> f.clock.at(f.end);
    assertThatThrownBy(f::confirm).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo("ACTIVITY_NOT_OPEN_FOR_CONFIRMATION");
    assertThat(f.cycle.getStatus()).isNotEqualTo("CONFIRMED");
    assertThat(f.auditRows).isEmpty(); assertThat(f.notifications).isEmpty();
  }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void oneMillisecondBeforeActivityEndStillAllowsConfirmation(Path path) {
    Fixture f = new Fixture(path); f.clock.at(f.end.minusNanos(1_000_000)); f.confirm();
    assertThat(f.cycle.getStatus()).isEqualTo("CONFIRMED");
    assertThat(f.cycle.getAttendanceStatus()).isEqualTo("PENDING");
  }

  @ParameterizedTest @EnumSource
  void publishedSuccessPreservesPendingAuditNotificationsAndAncestorFirstLockOrder(Path path) {
    Fixture f = new Fixture(path);
    f.confirm();
    assertThat(f.activity.getStatus()).isEqualTo("PUBLISHED");
    assertThat(f.cycle.getStatus()).isEqualTo("CONFIRMED");
    assertThat(f.cycle.getAttendanceStatus()).isEqualTo("PENDING");
    assertThat(f.auditRows).extracting(AuditLog::getAction)
        .containsExactly(path == Path.REVIEW ? "registration.reviewed" : path == Path.PROMOTION ? "promotion.accepted" : "registration.submitted");
    assertThat(f.notifications).hasSize(path == Path.PROMOTION ? 0 : 1);
    assertThat(f.transactions.committedOrders).singleElement().satisfies(order -> {
      assertThat(order).startsWith("activity", "member", "position", "cycle");
      if (path == Path.PROMOTION) assertThat(order).containsExactly("activity", "member", "position", "cycle", "offer");
    });
  }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void crossOrganizationParentIsRejectedBeforeConfirmation(Path path) {
    Fixture f = new Fixture(path); f.activity.setOrganizationId(99L);
    assertThatThrownBy(f::confirm).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo("ACTIVITY_NOT_FOUND");
    assertThat(f.cycle.getStatus()).isNotEqualTo("CONFIRMED");
    assertThat(f.auditRows).isEmpty(); assertThat(f.notifications).isEmpty();
  }

  @ParameterizedTest @EnumSource(value = Path.class, names = {"REVIEW", "PROMOTION"})
  void finalizerFirstBlocksThenRejectsQueuedConfirmationWithoutFinishedPending(Path path) throws Exception {
    Fixture f = new Fixture(path);
    CountDownLatch archived = new CountDownLatch(1), releaseFinalizer = new CountDownLatch(1);
    f.afterArchive = () -> { archived.countDown(); await(releaseFinalizer); };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> finalized = pool.submit(() -> f.finalizer.finalizeOne(11L, f.end));
      await(archived); f.clock.at(f.end);
      Future<Throwable> confirmation = pool.submit(() -> failureOf(f::confirm));
      // Signals either the new activity-lock attempt or the old unguarded confirmation write.
      await(f.confirmationAttempted);
      releaseFinalizer.countDown();
      assertThat(finalized.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(confirmation.get(5, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
      f.assertFinalInvariant();
      assertThat(f.auditRows).extracting(AuditLog::getAction).containsExactly("activity.finished");
    } finally { releaseFinalizer.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @EnumSource
  void confirmationFirstKeepsParentUntilCommitThenFinalizerArchivesIt(Path path) throws Exception {
    Fixture f = new Fixture(path);
    CountDownLatch beforeWrite = new CountDownLatch(1), releaseConfirmation = new CountDownLatch(1);
    AtomicReference<Boolean> holdsParent = new AtomicReference<>();
    f.beforeConfirmationWrite = () -> {
      holdsParent.set(f.activityLock.isHeldByCurrentThread()); beforeWrite.countDown(); await(releaseConfirmation);
    };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Throwable> confirmation = pool.submit(() -> failureOf(f::confirm));
      await(beforeWrite); f.clock.at(f.end);
      Future<Boolean> finalized = pool.submit(() -> f.finalizer.finalizeOne(11L, f.end));
      await(f.finalizerAttempted);
      // On the broken implementation there is no parent lock, so force finalization to commit before the late write.
      // On the fixed implementation the same schedule waits on the held ancestor; release to let confirmation commit first.
      if (!holdsParent.get()) assertThat(finalized.get(5, TimeUnit.SECONDS)).isTrue();
      releaseConfirmation.countDown();
      assertThat(confirmation.get(5, TimeUnit.SECONDS)).isNull();
      assertThat(finalized.get(5, TimeUnit.SECONDS)).isTrue();
      f.assertFinalInvariant();
      assertThat(f.cycle.getStatus()).isEqualTo("CONFIRMED");
      assertThat(f.cycle.getAttendanceStatus()).isEqualTo("NO_SHOW");
      assertThat(holdsParent.get()).isTrue();
      assertThat(f.transactions.committedOrders).hasSize(2);
    } finally { releaseConfirmation.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
  }

  private static Throwable failureOf(Runnable operation) {
    try { operation.run(); return null; } catch (Throwable failure) { return failure; }
  }
  private static void await(CountDownLatch latch) {
    try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded fixture wait timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-10T11:00:00Z"));
    void at(LocalDateTime now) { instant.set(now.toInstant(ZoneOffset.UTC)); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return instant.get(); }
  }

  private static final class Fixture {
    final Path path;
    final MutableClock clock = new MutableClock();
    final LocalDateTime end = LocalDateTime.of(2026, 10, 10, 12, 0);
    final Activity activity = new Activity();
    volatile RegistrationCycle cycle;
    final PromotionOffer offer = new PromotionOffer();
    final ReentrantLock activityLock = new ReentrantLock(), memberLock = new ReentrantLock(),
        positionLock = new ReentrantLock(), cycleLock = new ReentrantLock(), offerLock = new ReentrantLock();
    final List<AuditLog> auditRows = new CopyOnWriteArrayList<>();
    final List<NotificationWriter.NotificationCommand> notifications = new CopyOnWriteArrayList<>();
    final CountDownLatch confirmationAttempted = new CountDownLatch(1), finalizerAttempted = new CountDownLatch(1);
    Runnable afterArchive = () -> {}, beforeConfirmationWrite = () -> {}, afterPositionLock = () -> {};
    final ModelTransactions transactions = new ModelTransactions();
    final ActivityFinalizationService finalizer;
    final RegistrationReviewService review;
    final PromotionService promotion;
    final RegistrationSubmissionService submission;

    Fixture(Path path) {
      this.path = path;
      activity.setId(11L); activity.setOrganizationId(7L); activity.setStatus("PUBLISHED");
      activity.setActivityEndAt(end); activity.setRegistrationStartAt(end.minusHours(2));
      activity.setRegistrationEndAt(end.minusMinutes(50));
      activity.setActivityStartAt(end.minusMinutes(30));
      Registration registration = new Registration();
      registration.setId(100L); registration.setActivityId(11L); registration.setOrganizationId(7L);
      registration.setUserId(21L); registration.setLastCycleNumber(0); registration.setVersion(0);
      cycle = new RegistrationCycle();
      cycle.setId(101L); cycle.setRegistrationId(100L); cycle.setActivityId(11L); cycle.setOrganizationId(7L);
      cycle.setPositionId(20L); cycle.setUserId(21L); cycle.setCycleNumber(1); cycle.setVersion(0);
      cycle.setStatus(path == Path.REVIEW ? "PENDING_REVIEW" : "WAITLISTED"); cycle.setAttendanceStatus("NOT_REQUIRED");
      cycle.setWaitlistSequence(path == Path.PROMOTION ? 1L : null);
      ActivityPosition position = new ActivityPosition();
      position.setId(20L); position.setOrganizationId(7L); position.setActivityId(11L); position.setStatus("ACTIVE");
      position.setRegistrationMode(path == Path.REVIEW ? "REVIEW" : "FIRST_COME");
      position.setCapacity(10); position.setNextWaitlistSequence(1L); position.setPromotionTimeoutMinutes(30);
      offer.setId(300L); offer.setOrganizationId(7L); offer.setActivityId(11L); offer.setPositionId(20L);
      offer.setRegistrationCycleId(101L); offer.setStatus("PENDING"); offer.setExpiresAt(end.plusHours(1)); offer.setVersion(0);

      ActivityMapper activities = mock(ActivityMapper.class);
      ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
      RegistrationMapper registrations = mock(RegistrationMapper.class);
      RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
      PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
      OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
      AuditLogMapper audits = mock(AuditLogMapper.class);
      OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
      var policies = proxy(new ActivityRegistrationPolicyService(activities, positions,
          mock(ActivityQuestionMapper.class), mock(ActivityPositionQuestionMapper.class), authorization, new ObjectMapper()));
      AuditService audit = new AuditService(audits, new ObjectMapper());
      NotificationWriter writer = new NotificationWriter() {
        @Override public void write(NotificationCommand command) { notifications.add(command); transactions.tx().notifications.add(command); }
        @Override public int writeMany(Collection<Long> userIds, NotificationTemplate template) { throw new UnsupportedOperationException(); }
      };

      when(activities.selectByIdForUpdate(11L)).thenAnswer(call -> {
        if (transactions.tx().oldActivity == null) confirmationAttempted.countDown();
        transactions.lock("activity", activityLock); return activity;
      });
      when(activities.selectById(11L)).thenReturn(activity);
      when(activities.selectByIdForSubmissionRevalidation(11L)).thenReturn(activity);
      when(activities.claimFinished(11L, end)).thenAnswer(call -> {
        finalizerAttempted.countDown(); transactions.lock("activity", activityLock);
        if (!"PUBLISHED".equals(activity.getStatus())) return 0;
        transactions.tx().oldActivity = activity.getStatus(); activity.setStatus("FINISHED"); return 1;
      });
      when(members.selectActiveMemberForUpdate(21L, 7L)).thenAnswer(call -> {
        transactions.lock("member", memberLock); return new OrganizationMember();
      });
      when(positions.selectById(20L)).thenReturn(position);
      when(positions.selectByIdForUpdate(20L)).thenAnswer(call -> {
        transactions.lock("position", positionLock); afterPositionLock.run(); return position;
      });
      when(registrations.selectById(100L)).thenReturn(registration);
      when(registrations.selectByActivityAndUser(11L, 21L)).thenReturn(path == Path.SUBMISSION ? registration : null);
      when(cycles.selectActiveOrLatest(100L)).thenAnswer(call -> copy(cycle));
      when(cycles.selectById(101L)).thenAnswer(call -> copy(cycle));
      when(cycles.selectActiveCycleForUpdate(100L)).thenAnswer(call -> {
        transactions.lock("cycle", cycleLock); return path == Path.SUBMISSION ? null : copy(cycle);
      });
      when(cycles.selectByIdForUpdate(101L)).thenAnswer(call -> { transactions.lock("cycle", cycleLock); return copy(cycle); });
      when(cycles.updateById(any(RegistrationCycle.class))).thenAnswer(call -> {
        confirmationAttempted.countDown(); beforeConfirmationWrite.run();
        transactions.tx().oldCycle = copy(cycle); cycle = copy((RegistrationCycle) call.getArgument(0)); return 1;
      });
      when(cycles.insert(any(RegistrationCycle.class))).thenAnswer(call -> {
        confirmationAttempted.countDown(); beforeConfirmationWrite.run(); transactions.lock("cycle", cycleLock);
        transactions.tx().oldCycle = copy(cycle); cycle = copy((RegistrationCycle) call.getArgument(0)); return 1;
      });
      when(cycles.countCurrentConfirmedForActivity(7L, 11L)).thenAnswer(call -> "CONFIRMED".equals(cycle.getStatus()) ? 1L : 0L);
      when(cycles.finalizeCurrentConfirmedAttendance(7L, 11L)).thenAnswer(call -> {
        int affected = 0;
        if ("CONFIRMED".equals(cycle.getStatus())) {
          transactions.lock("cycle", cycleLock); transactions.tx().oldCycle = copy(cycle);
          cycle.setAttendanceStatus("NO_SHOW"); affected = 1;
        }
        afterArchive.run(); return affected;
      });
      when(offers.selectById(300L)).thenReturn(offer);
      when(offers.selectByIdForUpdate(300L)).thenAnswer(call -> { transactions.lock("offer", offerLock); return copy(offer); });
      when(offers.currentDatabaseTime()).thenAnswer(call -> LocalDateTime.now(clock));
      when(offers.updateById(any(PromotionOffer.class))).thenAnswer(call -> {
        transactions.tx().oldOffer = copy(offer); PromotionOffer written = call.getArgument(0);
        offer.setStatus(written.getStatus()); offer.setRespondedAt(written.getRespondedAt()); return 1;
      });
      when(audits.insert(any(AuditLog.class))).thenAnswer(call -> {
        AuditLog log = call.getArgument(0); auditRows.add(log); transactions.tx().audits.add(log); return 1;
      });
      finalizer =
          proxy(
              new ActivityFinalizationService(
                  activities,
                  cycles,
                  audit,
                  mock(com.volunteerflow.activity.ActivityDetailCacheInvalidator.class)));
      review = proxy(new RegistrationReviewService(registrations, cycles, positions, policies, members, offers, authorization, audit, writer, clock));
      promotion = proxy(new PromotionService(policies, members, registrations, cycles, offers, authorization, audit, writer));
      submission = proxy(new RegistrationSubmissionService(policies, members, registrations, cycles, mock(RegistrationAnswerMapper.class),
          offers, new RegistrationAnswerValidator(), audit, writer, clock));
    }

    void confirm() {
      switch (path) {
        case REVIEW -> review.decide(9L, 100L, new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", "eligible"));
        case PROMOTION -> promotion.respond(21L, 300L, new PromotionService.PromotionResponseRequest("ACCEPT"));
        case SUBMISSION -> submission.submit(21L, 11L, new RegistrationSubmissionService.SubmitRegistrationRequest(20L, List.of()));
      }
    }
    void assertFinalInvariant() {
      assertThat(activity.getStatus()).isEqualTo("FINISHED");
      assertThat("CONFIRMED".equals(cycle.getStatus()) && "PENDING".equals(cycle.getAttendanceStatus())).isFalse();
    }
    @SuppressWarnings("unchecked") <T> T proxy(T target) {
      ProxyFactory factory = new ProxyFactory(target);
      factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
      return (T) factory.getProxy();
    }

    final class ModelTransactions extends AbstractPlatformTransactionManager {
      final ThreadLocal<Tx> current = new ThreadLocal<>();
      final List<List<String>> committedOrders = new CopyOnWriteArrayList<>();
      Tx tx() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return current.get(); }
      void lock(String name, ReentrantLock lock) {
        Tx tx = tx();
        if (tx.locks.containsKey(name)) return;
        int rank = List.of("activity", "member", "position", "cycle", "offer").indexOf(name);
        assertThat(rank).as("No ancestor lock may be acquired after a downstream lock").isGreaterThanOrEqualTo(tx.lastRank);
        try { if (!lock.tryLock(5, TimeUnit.SECONDS)) throw new AssertionError("Lock timeout: " + name); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        tx.locks.put(name, lock); tx.lastRank = rank;
      }
      @Override protected Object doGetTransaction() { return new Holder(current.get()); }
      @Override protected boolean isExistingTransaction(Object transaction) { return ((Holder) transaction).tx != null; }
      @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
        Tx tx = new Tx(); ((Holder) transaction).tx = tx; current.set(tx);
      }
      @Override protected void doCommit(DefaultTransactionStatus status) {
        committedOrders.add(new ArrayList<>(current.get().locks.keySet()));
      }
      @Override protected void doRollback(DefaultTransactionStatus status) {
        Tx tx = current.get();
        if (tx.oldActivity != null) activity.setStatus(tx.oldActivity);
        if (tx.oldCycle != null) cycle = copy(tx.oldCycle);
        if (tx.oldOffer != null) { offer.setStatus(tx.oldOffer.getStatus()); offer.setRespondedAt(tx.oldOffer.getRespondedAt()); }
        auditRows.removeAll(tx.audits); notifications.removeAll(tx.notifications);
      }
      @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { current.get().rollbackOnly = true; }
      @Override protected void doCleanupAfterCompletion(Object transaction) {
        List<ReentrantLock> held = new ArrayList<>(current.get().locks.values()); Collections.reverse(held);
        held.forEach(ReentrantLock::unlock); current.remove();
      }
    }
  }
  private static final class Tx {
    final Map<String, ReentrantLock> locks = new LinkedHashMap<>();
    final List<AuditLog> audits = new ArrayList<>();
    final List<NotificationWriter.NotificationCommand> notifications = new ArrayList<>();
    int lastRank = -1; boolean rollbackOnly; String oldActivity; RegistrationCycle oldCycle; PromotionOffer oldOffer;
  }
  private static final class Holder implements SmartTransactionObject {
    Tx tx; Holder(Tx tx) { this.tx = tx; }
    @Override public boolean isRollbackOnly() { return tx != null && tx.rollbackOnly; }
    @Override public void flush() {}
  }
  private static RegistrationCycle copy(RegistrationCycle source) {
    RegistrationCycle row = new RegistrationCycle();
    row.setId(source.getId()); row.setOrganizationId(source.getOrganizationId()); row.setActivityId(source.getActivityId());
    row.setRegistrationId(source.getRegistrationId()); row.setPositionId(source.getPositionId()); row.setUserId(source.getUserId());
    row.setStatus(source.getStatus()); row.setAttendanceStatus(source.getAttendanceStatus()); row.setCycleNumber(source.getCycleNumber());
    row.setVersion(source.getVersion()); row.setWaitlistSequence(source.getWaitlistSequence()); return row;
  }
  private static PromotionOffer copy(PromotionOffer source) {
    PromotionOffer row = new PromotionOffer();
    row.setId(source.getId()); row.setOrganizationId(source.getOrganizationId()); row.setActivityId(source.getActivityId());
    row.setPositionId(source.getPositionId()); row.setRegistrationCycleId(source.getRegistrationCycleId());
    row.setStatus(source.getStatus()); row.setExpiresAt(source.getExpiresAt()); row.setVersion(source.getVersion());
    row.setRespondedAt(source.getRespondedAt()); return row;
  }
}
