package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.*;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Only the external database boundary is modelled; services, promotion rules and Spring advice run. */
final class ReconciliationFixture {
  LocalDateTime now = LocalDateTime.of(2026, 10, 19, 10, 0);
  final Map<Long, PromotionReconcileTask> rows = new LinkedHashMap<>();
  final List<PromotionOffer> activeOffers = new ArrayList<>();
  final List<RegistrationCycle> waitlist = new ArrayList<>();
  final List<String> operations = new ArrayList<>();
  final List<Integer> discoveryLimits = new ArrayList<>();
  final List<String> claimOwners = new ArrayList<>();
  final Set<Long> failedPositions = new HashSet<>();
  boolean failCompletion;
  boolean failRequest;
  final Transactions transactions = new Transactions();
  final PromotionReconciliationProperties properties = new PromotionReconciliationProperties();
  final ActivityRegistrationPolicyService policies = mock(ActivityRegistrationPolicyService.class);
  final ActivityPositionMapper positions = mock(ActivityPositionMapper.class);
  final Activity activity = new Activity();
  final AuditService audit = mock(AuditService.class);
  final CapturingDomainEventPublisher events = new CapturingDomainEventPublisher();
  Runnable afterPositionLock = () -> {};
  final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  final PromotionOfferMapper offers = mock(PromotionOfferMapper.class);
  final ActivityPosition position = PromotionServiceTest.position();
  final PromotionReconcileTaskMapper mapper = (PromotionReconcileTaskMapper) Proxy.newProxyInstance(
      PromotionReconcileTaskMapper.class.getClassLoader(), new Class<?>[]{PromotionReconcileTaskMapper.class}, this::database);
  final PromotionReconciliationRequester requester = proxy(new PromotionReconciliationRequester(mapper));
  final PromotionService promotions;
  final PromotionReconciliationService service;

  ReconciliationFixture() {
    activity.setId(11L); activity.setOrganizationId(10L); activity.setStatus("PUBLISHED");
    activity.setRegistrationEndAt(now.minusHours(1)); activity.setActivityEndAt(now.plusHours(2));
    when(policies.activityForPromotion(10L, 11L)).thenReturn(activity);
    when(positions.selectById(anyLong())).thenAnswer(call -> {
      operations.add("snapshot");
      if (!Objects.equals(call.getArgument(0), 20L)) return null;
      ActivityPosition snapshot = new ActivityPosition();
      org.springframework.beans.BeanUtils.copyProperties(position, snapshot); return snapshot;
    });
    when(policies.lockActivityForRegistrationChange(10L, 11L)).thenAnswer(call -> {
      operations.add("activity"); return activity;
    });
    position.setCapacity(1);
    waitlist.add(PromotionServiceTest.cycle(101L, 100L, 21L));
    waitlist.add(PromotionServiceTest.cycle(102L, 200L, 22L));
    when(policies.lockPositionForRegistrationChange(anyLong())).thenAnswer(call -> {
      operations.add("position");
      if (failedPositions.contains(call.getArgument(0))) throw new IllegalStateException("secret token");
      afterPositionLock.run();
      return Objects.equals(call.getArgument(0), 20L) ? position : null;
    });
    when(cycles.selectFirstWaitlistedForUpdate(20L)).thenAnswer(call -> {
      operations.add("cycle");
      return waitlist.stream().filter(c -> activeOffers.stream()
          .noneMatch(o -> o.getRegistrationCycleId().equals(c.getId()))).findFirst().orElse(null);
    });
    when(offers.countActiveReservations(20L)).thenAnswer(call -> (long) activeOffers.size());
    when(offers.currentDatabaseTime()).thenAnswer(call -> now);
    when(offers.insert(any(PromotionOffer.class))).thenAnswer(call -> {
      assertTransaction(); operations.add("offer"); activeOffers.add(call.getArgument(0)); return 1;
    });
    promotions = proxy(new PromotionService(policies, members, mock(RegistrationMapper.class), cycles,
        offers, mock(OrganizationAuthorizationService.class), audit, events, requester));
    service = proxy(new PromotionReconciliationService(mapper, positions, policies, promotions, properties));
  }

  PromotionReconcileTask row(long id) { return rows.get(id); }
  void request() { requestAt(now); }
  void requestAt(LocalDateTime due) { inTransaction(() -> requester.request(10L, 20L, due)); }
  void inTransaction(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(s -> action.run()); }
  PromotionReconcileTask task(long id, String status, LocalDateTime due) {
    PromotionReconcileTask task = new PromotionReconcileTask();
    task.setPositionId(id); task.setOrganizationId(10L); task.setStatus(status);
    task.setRequestVersion(1L); task.setProcessedVersion(0L); task.setAttemptCount(0); task.setNextRunAt(due);
    rows.put(id, task); return task;
  }

  private Object database(Object proxy, Method method, Object[] a) throws Throwable {
    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, a);
    String name = method.getName();
    if (name.equals("currentDatabaseTime")) return now;
    assertTransaction();
    if (name.equals("upsertRequest")) {
      if (failRequest) return 0;
      PromotionReconcileTask row = rows.get(a[1]);
      if (row == null) { task((long) a[1], "PENDING", (LocalDateTime) a[2]); return 1; }
      row.setRequestVersion(row.getRequestVersion() + 1);
      if (((LocalDateTime) a[2]).isBefore(row.getNextRunAt())) row.setNextRunAt((LocalDateTime) a[2]);
      if (!"PROCESSING".equals(row.getStatus())) row.setStatus("PENDING");
      return 2;
    }
    if (name.equals("selectDueForUpdate") || name.equals("selectExpiredLeasesForUpdate")) {
      discoveryLimits.add((int) a[1]);
      Comparator<PromotionReconcileTask> ordering = name.equals("selectDueForUpdate")
          ? Comparator.comparing(PromotionReconcileTask::getStatus)
              .thenComparing(PromotionReconcileTask::getNextRunAt).thenComparing(PromotionReconcileTask::getPositionId)
          : Comparator.comparing(PromotionReconcileTask::getLockedUntil).thenComparing(PromotionReconcileTask::getPositionId);
      return rows.values().stream().filter(r -> name.equals("selectDueForUpdate")
          ? Set.of("PENDING", "RETRY").contains(r.getStatus()) && !r.getNextRunAt().isAfter((LocalDateTime) a[0])
          : "PROCESSING".equals(r.getStatus()) && !r.getLockedUntil().isAfter((LocalDateTime) a[0]))
          .sorted(ordering).limit((int) a[1]).map(ReconciliationFixture::copy).toList();
    }
    PromotionReconcileTask row = rows.get(a[0]);
    if (name.equals("selectByPositionForUpdate")) { operations.add("task"); return row; }
    if (name.equals("claim")) {
      if (!Objects.equals(row.getRequestVersion(), a[1])) return 0;
      row.setStatus("PROCESSING"); row.setLockedBy((String) a[2]); row.setLockedUntil((LocalDateTime) a[3]);
      claimOwners.add((String) a[2]); return 1;
    }
    if (name.equals("markProcessed") || name.equals("markRetry")) {
      LocalDateTime at = (LocalDateTime) a[name.equals("markProcessed") ? 3 : 6];
      if (!"PROCESSING".equals(row.getStatus()) || !Objects.equals(row.getLockedBy(), a[2])
          || !row.getLockedUntil().isAfter(at) || row.getProcessedVersion() >= (long) a[1]
          || row.getRequestVersion() < (long) a[1]) return 0;
      if (name.equals("markProcessed")) {
        operations.add("complete");
        if (failCompletion) return 0;
        row.setProcessedVersion((long) a[1]);
        row.setStatus(row.getRequestVersion().equals(a[1]) ? "DONE" : "PENDING");
        row.setNextRunAt(at); row.setAttemptCount(0); row.setLastError(null);
      } else {
        row.setStatus("RETRY"); row.setAttemptCount((int) a[3]);
        row.setNextRunAt((LocalDateTime) a[4]); row.setLastError((String) a[5]);
      }
      row.setLockedBy(null); row.setLockedUntil(null); return 1;
    }
    throw new AssertionError("Unexpected DB operation " + name);
  }

  private static PromotionReconcileTask copy(PromotionReconcileTask row) {
    PromotionReconcileTask copy = new PromotionReconcileTask();
    org.springframework.beans.BeanUtils.copyProperties(row, copy); return copy;
  }
  private void assertTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
  @SuppressWarnings("unchecked") <T> T proxy(T target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }
  final class Transactions extends AbstractPlatformTransactionManager {
    int commits;
    int rollbacks;
    Map<Long, PromotionReconcileTask> before;
    List<PromotionOffer> offersBefore;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
      before = new LinkedHashMap<>(); rows.forEach((id, row) -> before.put(id, copy(row)));
      offersBefore = new ArrayList<>(activeOffers);
    }
    @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++; rows.clear(); rows.putAll(before); activeOffers.clear(); activeOffers.addAll(offersBefore);
    }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {}
  }
}
