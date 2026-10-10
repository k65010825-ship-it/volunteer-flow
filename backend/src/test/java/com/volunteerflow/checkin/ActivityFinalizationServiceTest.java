package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityMapper.DueActivityId;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.audit.AuditLogMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

class ActivityFinalizationServiceTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  private final AuditLogMapper logs = mock(AuditLogMapper.class);
  private final List<AuditLog> storedAudits = new ArrayList<>();
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0);
  private final Activity activity = new Activity();
  private final Map<Long, String> attendance = new LinkedHashMap<>();
  private ActivityFinalizationService service;

  @BeforeEach void setup() {
    activity.setId(11L); activity.setOrganizationId(7L); activity.setStatus("PUBLISHED");
    activity.setActivityEndAt(now);
    service = new ActivityFinalizationService(activities, cycles, new AuditService(logs, new ObjectMapper()));
  }

  @Test void losingClaimPerformsNoReadAttendanceOrAuditWork() {
    assertThat(service.finalizeOne(11L, now)).isFalse();
    verify(activities).claimFinished(11L, now);
    verifyNoMoreInteractions(activities);
    verifyNoInteractions(cycles, logs);
  }

  @ParameterizedTest @ValueSource(ints = {-1, 2})
  void impossibleClaimCountAborts(int rows) {
    when(activities.claimFinished(11L, now)).thenReturn(rows);
    assertThatThrownBy(() -> service.finalizeOne(11L, now)).isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(cycles, logs);
  }

  @Test void successfulClaimArchivesAttendanceAndWritesCheckedAudit() {
    arrangePersistence();
    assertThat(proxied(new ModelTransactions()).finalizeOne(11L, now)).isTrue();
    assertThat(activity.getStatus()).isEqualTo("FINISHED");
    assertThat(attendance).containsEntry(101L, "CHECKED_IN").containsEntry(102L, "NO_SHOW")
        .containsEntry(103L, "NOT_REQUIRED").containsEntry(104L, "PENDING");
    assertThat(storedAudits).singleElement().satisfies(log -> {
      assertThat(log.getAction()).isEqualTo("activity.finished");
      assertThat(log.getOrganizationId()).isEqualTo(7L);
      assertThat(log.getActorUserId()).isNull();
      assertThat(log.getResourceId()).isEqualTo(11L);
      assertThat(log.getBeforeJson()).contains("PUBLISHED");
      assertThat(log.getAfterJson()).contains("FINISHED");
    });
    var order = inOrder(activities, cycles, logs);
    order.verify(activities).claimFinished(11L, now);
    order.verify(activities).selectByIdForUpdate(11L);
    order.verify(cycles).countCurrentConfirmedForActivity(7L, 11L);
    order.verify(cycles).finalizeCurrentConfirmedAttendance(7L, 11L);
    order.verify(logs).insert(any(AuditLog.class));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void auditExceptionOrZeroInsertRollsBackClaimAndAttendanceAndRetryCanWin(boolean zeroRows) {
    arrangePersistence();
    ModelTransactions tx = new ModelTransactions();
    ActivityFinalizationService proxy = proxied(tx);
    if (zeroRows) when(logs.insert(any(AuditLog.class))).thenReturn(0);
    else when(logs.insert(any(AuditLog.class))).thenThrow(new IllegalStateException("audit unavailable"));
    assertThatThrownBy(() -> proxy.finalizeOne(11L, now)).isInstanceOf(RuntimeException.class);
    assertThat(activity.getStatus()).isEqualTo("PUBLISHED");
    assertThat(attendance).containsEntry(101L, "PENDING").containsEntry(102L, "PENDING");
    assertThat(storedAudits).isEmpty();
    assertThat(tx.rollbacks).isEqualTo(1);
    when(logs.insert(any(AuditLog.class))).thenAnswer(call -> { storedAudits.add(call.getArgument(0)); return 1; });
    assertThat(proxy.finalizeOne(11L, now)).isTrue();
    assertThat(activity.getStatus()).isEqualTo("FINISHED");
    assertThat(tx.commits).isEqualTo(1);
    assertThat(storedAudits).hasSize(1);
    assertThat(proxy.finalizeOne(11L, now)).isFalse();
    assertThat(storedAudits).hasSize(1);
  }

  @Test void attendanceCountMismatchRollsBackClaimWithoutAudit() {
    arrangePersistence();
    when(cycles.finalizeCurrentConfirmedAttendance(7L, 11L)).thenReturn(1);
    ModelTransactions tx = new ModelTransactions();
    assertThatThrownBy(() -> proxied(tx).finalizeOne(11L, now)).isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(activity.getStatus()).isEqualTo("PUBLISHED");
    assertThat(tx.rollbacks).isEqualTo(1);
    verifyNoInteractions(logs);
  }

  @Test void noConfirmedCyclesStillFinishesAndAudits() {
    when(activities.claimFinished(11L, now)).thenReturn(1);
    when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
    when(logs.insert(any(AuditLog.class))).thenAnswer(call -> { storedAudits.add(call.getArgument(0)); return 1; });
    assertThat(proxied(new ModelTransactions()).finalizeOne(11L, now)).isTrue();
    assertThat(storedAudits).hasSize(1);
  }

  @Test void missingActivityAfterClaimAbortsWithoutAttendanceOrAudit() {
    when(activities.claimFinished(11L, now)).thenReturn(1);
    assertThatThrownBy(() -> service.finalizeOne(11L, now)).isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(cycles, logs);
  }

  @Test void jobCallsSeparateRequiresNewProxyPerActivityAndSuspendsAmbientTransaction() {
    arrangePersistence();
    ModelTransactions tx = new ModelTransactions();
    var proxy = proxied(tx);
    var properties = new ActivityFinalizationProperties();
    properties.setBatchSize(2); properties.setMaxBatchesPerRun(1);
    when(activities.selectDueIds(now, null, null, 2))
        .thenReturn(List.of(new DueActivityId(11L, now), new DueActivityId(12L, now)));
    when(activities.claimFinished(12L, now)).thenAnswer(call -> {
      assertThat(tx.current.get().id).isEqualTo(3);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      return 0;
    });
    var job = new ActivityFinalizationJob(activities, proxy, properties,
        Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    new TransactionTemplate(tx).executeWithoutResult(status -> {
      assertThat(tx.current.get().id).isEqualTo(1);
      assertThat(job.finalizeBatch()).isEqualTo(1);
      assertThat(tx.current.get().id).isEqualTo(1);
    });
    assertThat(tx.propagations).containsExactly(TransactionDefinition.PROPAGATION_REQUIRED,
        TransactionDefinition.PROPAGATION_REQUIRES_NEW, TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(tx.suspensions).isEqualTo(2);
    assertThat(tx.commits).isEqualTo(3);
    assertThat(tx.current.get()).isNull();
  }

  private void arrangePersistence() {
    // Mapper doubles model external rows only; production service, audit, job and transaction advice stay real.
    // 101/102 are current CONFIRMED; 103 is non-confirmed; 104 is a superseded CONFIRMED cycle.
    attendance.put(101L, "PENDING"); attendance.put(102L, "PENDING");
    attendance.put(103L, "NOT_REQUIRED"); attendance.put(104L, "PENDING");
    when(activities.claimFinished(11L, now)).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      if (!"PUBLISHED".equals(activity.getStatus())) return 0;
      activity.setStatus("FINISHED"); return 1;
    });
    when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
    when(cycles.countCurrentConfirmedForActivity(7L, 11L)).thenReturn(2L);
    when(cycles.finalizeCurrentConfirmedAttendance(7L, 11L)).thenAnswer(call -> {
      attendance.put(101L, "CHECKED_IN"); attendance.put(102L, "NO_SHOW"); return 2;
    });
    when(logs.insert(any(AuditLog.class))).thenAnswer(call -> { storedAudits.add(call.getArgument(0)); return 1; });
  }

  private ActivityFinalizationService proxied(ModelTransactions tx) {
    ProxyFactory factory = new ProxyFactory(service);
    factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
    return (ActivityFinalizationService) factory.getProxy();
  }

  /** In-memory row/transaction model, not proof of a database rollback or MySQL concurrency. */
  private final class ModelTransactions extends AbstractPlatformTransactionManager {
    private final ThreadLocal<Tx> current = new ThreadLocal<>();
    private final List<Integer> propagations = new ArrayList<>();
    private int sequence, commits, rollbacks, suspensions;
    @Override protected Object doGetTransaction() { return new Holder(current.get()); }
    @Override protected boolean isExistingTransaction(Object transaction) { return ((Holder) transaction).tx != null; }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
      Tx tx = new Tx(++sequence, activity.getStatus(), new LinkedHashMap<>(attendance), storedAudits.size());
      ((Holder) transaction).tx = tx; current.set(tx); propagations.add(definition.getPropagationBehavior());
    }
    @Override protected Object doSuspend(Object transaction) {
      Holder holder = (Holder) transaction; Tx suspended = holder.tx;
      holder.tx = null; current.remove(); suspensions++; return suspended;
    }
    @Override protected void doResume(Object transaction, Object suspended) {
      ((Holder) transaction).tx = (Tx) suspended; current.set((Tx) suspended);
    }
    @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      Tx tx = ((Holder) status.getTransaction()).tx;
      activity.setStatus(tx.activityStatus); attendance.clear(); attendance.putAll(tx.attendance);
      storedAudits.subList(tx.auditCount, storedAudits.size()).clear(); rollbacks++;
    }
    @Override protected void doCleanupAfterCompletion(Object transaction) { current.remove(); }
  }
  private static final class Holder { private Tx tx; private Holder(Tx tx) { this.tx = tx; } }
  private record Tx(int id, String activityStatus, Map<Long, String> attendance, int auditCount) {}
}
