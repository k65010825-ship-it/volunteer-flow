package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.checkin.CheckinSessionService.OpenSessionRequest;
import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.CapturingNotificationWriter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.ibatis.mapping.BoundSql;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CheckinSessionServiceTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final CheckinSessionMapper sessions = mock(CheckinSessionMapper.class);
  private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  private final AuditService audit = mock(AuditService.class);
  private final CapturingNotificationWriter notifications = new CapturingNotificationWriter();
  private final CheckinCodeGenerator codes = new SecureCheckinCodeGenerator(new SecureRandom());
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T04:00:00Z"), ZoneOffset.UTC);
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 8, 4, 0);
  private final CheckinSessionService service = new CheckinSessionService(
      activities, sessions, authorization, audit, notifications, codes, clock);
  private final List<CheckinSession> inserted = new ArrayList<>();
  private Activity activity;

  @BeforeEach
  void arrange() {
    activity = new Activity();
    activity.setId(11L);
    activity.setOrganizationId(10L);
    activity.setTitle("校园志愿活动");
    activity.setStatus("PUBLISHED");
    activity.setActivityEndAt(now.plusHours(3));
    when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
    when(activities.selectById(11L)).thenReturn(activity);
    when(sessions.insertWithDigest(any(CheckinSession.class), any(byte[].class)))
        .thenAnswer(invocation -> {
          CheckinSession row = invocation.getArgument(0);
          row.setStaticCodeHash(HexFormat.of().formatHex(invocation.getArgument(1)));
          inserted.add(row);
          return 1;
        });
    when(sessions.insert(any(CheckinSession.class))).thenAnswer(invocation -> {
      inserted.add(invocation.getArgument(0));
      return 1;
    });
    when(sessions.selectConfirmedUserIds(10L, 11L)).thenReturn(List.of(21L, 22L, 21L));
    when(sessions.closeOpen(anyLong(), anyLong(), anyString(), any())).thenReturn(1);
  }

  @Test
  void defaultsToFifteenMinutesAndReturnsPlaintextOnlyInOpenedResult() throws Exception {
    var opened = service.open(7L, 11L, new OpenSessionRequest(null));
    assertThat(opened.code()).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$");
    assertThat(opened.activityId()).isEqualTo(11L);
    assertThat(opened.opensAt()).isEqualTo(now);
    assertThat(opened.closesAt()).isEqualTo(now.plusMinutes(15));
    CheckinSession persisted = inserted.get(0);
    assertThat(persisted.getStaticCodeHash()).matches("^[a-f0-9]{64}$");
    assertThat(HexFormat.of().parseHex(persisted.getStaticCodeHash())).isEqualTo(codes.digest(opened.code()));
    assertThat(persisted.getCreatedBy()).isEqualTo(7L);
    assertThat(persisted.getVersion()).isZero();
    assertThat(persisted.getSessionType()).isEqualTo("STATIC_CODE");
    assertThat(persisted.getStatus()).isEqualTo("OPEN");
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.writeValueAsString(persisted)).doesNotContain(opened.code(), persisted.getStaticCodeHash());
    assertThat(opened.toString()).doesNotContain(opened.code());
    verify(audit).record(10L, 7L, "checkin.session.opened", "checkin_session", opened.sessionId(), null);
    assertThat(notifications.commands()).hasSize(2).allSatisfy(command -> {
      assertThat(command.organizationId()).isEqualTo(10L);
      assertThat(command.type()).isEqualTo("CHECKIN_OPENED");
      assertThat(command.businessType()).isEqualTo("ACTIVITY");
      assertThat(command.businessId()).isEqualTo(11L);
      assertThat(command.deduplicationKey()).isEqualTo("checkin-session:" + opened.sessionId() + ":opened");
      assertThat(command.content()).doesNotContain(opened.code(), persisted.getStaticCodeHash());
    });
    assertThat(notifications.commands()).extracting(command -> command.userId()).containsExactly(21L, 22L);
  }

  @Test
  void omittedSessionTypeStillCreatesStaticSession() {
    var opened = service.open(7L, 11L, new OpenSessionRequest(15, null));

    assertThat(opened.sessionType()).isEqualTo("STATIC_CODE");
    assertThat(opened.code()).isNotBlank();
    verify(sessions).insertWithDigest(any(CheckinSession.class), any(byte[].class));
  }

  @Test
  void dynamicSessionStoresNoStaticDigestOrPlaintextCode() {
    var opened = service.open(7L, 11L, new OpenSessionRequest(15, "DYNAMIC_QR"));

    assertThat(opened.sessionType()).isEqualTo("DYNAMIC_QR");
    assertThat(opened.code()).isNull();
    assertThat(inserted).singleElement().satisfies(row -> {
      assertThat(row.getSessionType()).isEqualTo("DYNAMIC_QR");
      assertThat(row.getStaticCodeHash()).isNull();
    });
    verify(sessions).insert(any(CheckinSession.class));
    verify(sessions, never()).insertWithDigest(any(), any());
  }

  @Test
  void rejectsUnknownSessionType() {
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(15, "QR")), "INVALID_CHECKIN_SESSION_TYPE");
    assertThat(inserted).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 120})
  void acceptsInclusiveDurationBounds(int minutes) {
    assertThat(service.open(7L, 11L, new OpenSessionRequest(minutes)).closesAt())
        .isEqualTo(now.plusMinutes(minutes));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 4, 121, Integer.MAX_VALUE})
  void rejectsDurationOutsideBoundsWithoutWriting(int minutes) {
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(minutes)), "INVALID_CHECKIN_DURATION");
    assertThat(inserted).isEmpty();
    verifyNoInteractions(audit);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void deniedManagerCannotObserveSessionOrWriteAnything() {
    BusinessException denied = new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Denied");
    doThrow(denied).when(authorization).requirePermission(7L, 10L, "checkin:manage");
    assertThatThrownBy(() -> service.open(7L, 11L, new OpenSessionRequest(15))).isSameAs(denied);
    verifyNoInteractions(sessions, audit);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void missingActivityFailsBeforeSessionLookup() {
    rejects(() -> service.open(7L, 99L, new OpenSessionRequest(15)), "ACTIVITY_NOT_FOUND");
    verifyNoInteractions(sessions, authorization, audit);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELED", "FINISHED"})
  void rejectsFinalActivity(String status) {
    activity.setStatus(status);
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(15)), "ACTIVITY_FINAL");
    verifyNoInteractions(sessions, audit);
  }

  @Test
  void rejectsDraftActivity() {
    activity.setStatus("DRAFT");
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(15)), "ACTIVITY_CHECKIN_NOT_ALLOWED");
  }

  @Test
  void rejectsActivityAtExactEndBoundary() {
    activity.setActivityEndAt(now);
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(15)), "ACTIVITY_FINAL");
    verifyNoInteractions(sessions, audit);
  }

  @Test
  void effectiveOpenSessionPreventsAnotherCodeGenerationOrInsert() {
    when(sessions.selectEffectiveOpenForUpdate(11L, now)).thenReturn(session(101L, "OPEN", now.plusMinutes(1)));
    rejects(() -> service.open(7L, 11L, new OpenSessionRequest(15)), "CHECKIN_SESSION_ALREADY_OPEN");
    assertThat(inserted).isEmpty();
    verifyNoInteractions(audit);
  }

  @Test
  void locksActivityBeforeExpiryCleanupAndEffectiveSessionRead() {
    service.open(7L, 11L, new OpenSessionRequest(15));
    var order = inOrder(activities, authorization, sessions, audit);
    order.verify(activities).selectByIdForUpdate(11L);
    order.verify(authorization).requirePermission(7L, 10L, "checkin:manage");
    order.verify(sessions).expireOpen(11L, now);
    order.verify(sessions).selectEffectiveOpenForUpdate(11L, now);
    order.verify(sessions).insertWithDigest(any(), any());
    order.verify(audit).record(eq(10L), eq(7L), eq("checkin.session.opened"), eq("checkin_session"), anyLong(), isNull());
  }

  @Test
  void expiryCleanupIncludesExactBoundaryAndAllowsReplacement() {
    AtomicReference<CheckinSession> existing = new AtomicReference<>(session(101L, "OPEN", now));
    doAnswer(invocation -> {
      CheckinSession row = existing.get();
      if ("OPEN".equals(row.getStatus()) && !row.getClosesAt().isAfter(invocation.getArgument(1))) {
        row.setStatus("EXPIRED");
        return 1;
      }
      return 0;
    }).when(sessions).expireOpen(11L, now);
    when(sessions.selectEffectiveOpenForUpdate(11L, now)).thenAnswer(invocation ->
        "OPEN".equals(existing.get().getStatus()) ? existing.get() : null);
    service.open(7L, 11L, new OpenSessionRequest(15));
    assertThat(existing.get().getStatus()).isEqualTo("EXPIRED");
    assertThat(inserted).hasSize(1);
  }

  @Test
  void zeroRowInsertFailsBeforeAuditAndNotifications() {
    when(sessions.insertWithDigest(any(), any())).thenReturn(0);
    assertThatThrownBy(() -> service.open(7L, 11L, new OpenSessionRequest(15)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(audit);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void currentMemberAndManagerViewsNeverReturnCodeOrDigest() throws Exception {
    var opened = service.open(7L, 11L, new OpenSessionRequest(15));
    when(sessions.selectEffectiveOpen(11L, now)).thenReturn(inserted.get(0));
    SessionView member = service.currentForMember(21L, 11L);
    SessionView manager = service.currentForManager(7L, 11L);
    assertThat(member.sessionId()).isEqualTo(opened.sessionId());
    assertThat(manager).isEqualTo(member);
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    assertThat(json.writeValueAsString(List.of(member, manager)))
        .doesNotContain(opened.code(), inserted.get(0).getStaticCodeHash(), "code", "hash", "digest");
    verify(authorization).requireMembership(21L, 10L);
    verify(authorization, times(2)).requirePermission(7L, 10L, "checkin:manage");
  }

  @Test
  void currentViewsEnforceMembershipAndManagerPermissionBeforeReadingSessions() {
    doThrow(new BusinessException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Hidden"))
        .when(authorization).requireMembership(99L, 10L);
    doThrow(new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Denied"))
        .when(authorization).requirePermission(21L, 10L, "checkin:manage");
    rejects(() -> service.currentForMember(99L, 11L), "ORGANIZATION_NOT_FOUND");
    rejects(() -> service.currentForManager(21L, 11L), "PERMISSION_DENIED");
    verifyNoInteractions(sessions);
  }

  @Test
  void currentReturnsNullWhenThereIsNoEffectiveSessionOrActivityHasEnded() {
    assertThat(service.currentForMember(21L, 11L)).isNull();
    activity.setActivityEndAt(now);
    assertThat(service.currentForManager(7L, 11L)).isNull();
    verify(sessions, times(1)).selectEffectiveOpen(11L, now);
  }

  @Test
  void closeIsIdempotentAndLocksActivityBeforeSession() {
    CheckinSession row = session(101L, "OPEN", now.plusMinutes(15));
    arrangeClose(row);
    when(sessions.closeOpen(101L, 11L, "CLOSED", now)).thenAnswer(invocation -> {
      row.setStatus("CLOSED");
      row.setClosesAt(now);
      return 1;
    });
    SessionView first = service.close(7L, 101L);
    SessionView repeated = service.close(7L, 101L);
    assertThat(first.status()).isEqualTo("CLOSED");
    assertThat(first.closesAt()).isEqualTo(now);
    assertThat(repeated).isEqualTo(first);
    verify(sessions, times(1)).closeOpen(101L, 11L, "CLOSED", now);
    verify(audit, times(1)).record(10L, 7L, "checkin.session.closed", "checkin_session", 101L, null);
    var order = inOrder(activities, sessions);
    order.verify(sessions).selectById(101L);
    order.verify(activities).selectByIdForUpdate(11L);
    order.verify(sessions).selectByIdForUpdate(101L, 11L);
    assertThat(notifications.commands()).isEmpty();
  }

  @Test
  void closeExpiredOpenMarksExpiredWithoutReopeningOrExtendingTime() {
    arrangeClose(session(101L, "OPEN", now.minusMinutes(1)));
    assertThat(service.close(7L, 101L).status()).isEqualTo("EXPIRED");
    verify(sessions).closeOpen(101L, 11L, "EXPIRED", now);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLOSED", "EXPIRED"})
  void closingTerminalSessionDoesNotWriteAgain(String status) {
    arrangeClose(session(101L, status, now.minusMinutes(1)));
    assertThat(service.close(7L, 101L).status()).isEqualTo(status);
    verify(sessions, never()).closeOpen(anyLong(), anyLong(), anyString(), any());
    verifyNoInteractions(audit);
  }

  @Test
  void closePermissionDenialDoesNotLockOrMutateSession() {
    arrangeClose(session(101L, "OPEN", now.plusMinutes(15)));
    doThrow(new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Denied"))
        .when(authorization).requirePermission(7L, 10L, "checkin:manage");
    rejects(() -> service.close(7L, 101L), "PERMISSION_DENIED");
    verify(sessions, never()).selectByIdForUpdate(anyLong(), anyLong());
    verifyNoInteractions(audit);
  }

  @Test
  void missingAndMismatchedSessionsAreHidden() {
    rejects(() -> service.close(7L, 999L), "CHECKIN_SESSION_NOT_FOUND");
    CheckinSession otherOrganization = session(101L, "OPEN", now.plusMinutes(15));
    otherOrganization.setOrganizationId(999L);
    arrangeClose(otherOrganization);
    rejects(() -> service.close(7L, 101L), "CHECKIN_SESSION_NOT_FOUND");
    verify(sessions, never()).closeOpen(anyLong(), anyLong(), anyString(), any());
  }

  @Test
  void zeroRowClosureFailsWithoutAuditing() {
    arrangeClose(session(101L, "OPEN", now.plusMinutes(15)));
    when(sessions.closeOpen(101L, 11L, "CLOSED", now)).thenReturn(0);
    assertThatThrownBy(() -> service.close(7L, 101L)).isInstanceOf(OptimisticLockingFailureException.class);
    verifyNoInteractions(audit);
  }

  @Test
  void repositoryBoundaryPersistsLowercaseHexInsteadOfBytesOrPlaintext() {
    CheckinSessionMapper boundary = mock(CheckinSessionMapper.class, CALLS_REAL_METHODS);
    when(boundary.insert(any(CheckinSession.class))).thenReturn(1);
    CheckinSession row = session(101L, "OPEN", now.plusMinutes(15));
    byte[] digest = HexFormat.of().parseHex("a00d76646eba91b057841554d5c8334f498dc592ed744bce404f21fe271cd36e");
    assertThat(boundary.insertWithDigest(row, digest)).isEqualTo(1);
    assertThat(row.getStaticCodeHash()).isEqualTo("a00d76646eba91b057841554d5c8334f498dc592ed744bce404f21fe271cd36e");
    verify(boundary).insert(row);
  }

  @Test
  void mapperQueriesApplyValidityTenantAndConfirmedRecipientBoundaries() {
    MybatisConfiguration config = new MybatisConfiguration();
    config.addMapper(CheckinSessionMapper.class);
    String effective = sql(config, "selectEffectiveOpenForUpdate", Map.of("activityId", 11L, "now", now));
    assertThat(effective).contains("status='OPEN'", "closes_at > ?", "ORDER BY opens_at DESC,id DESC LIMIT 1 FOR UPDATE");
    String current = sql(config, "selectEffectiveOpen", Map.of("activityId", 11L, "now", now));
    assertThat(current).contains("opens_at <= ?", "closes_at > ?").doesNotContain("FOR UPDATE");
    String expire = sql(config, "expireOpen", Map.of("activityId", 11L, "now", now));
    assertThat(expire).contains("status='EXPIRED'", "status='OPEN'", "closes_at <= ?", "version=version+1");
    String recipients = sql(config, "selectConfirmedUserIds", Map.of("organizationId", 10L, "activityId", 11L));
    assertThat(recipients).contains("DISTINCT", "organization_id=?", "activity_id=?", "status='CONFIRMED'");
  }

  @Test
  void proxiedConcurrentOpensSerializeAtActivityRowAndOnlyOneWins() throws Exception {
    // This models the database lock at the mapper boundary; production has no JVM lock.
    DatabaseTransactions tx = new DatabaseTransactions(inserted);
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch secondAttempt = new CountDownLatch(1);
    CountDownLatch firstInserted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> {
      if (attempts.incrementAndGet() == 2) secondAttempt.countDown();
      tx.lockActivity();
      return activity;
    });
    when(sessions.selectEffectiveOpenForUpdate(11L, now)).thenAnswer(invocation ->
        inserted.stream().filter(row -> "OPEN".equals(row.getStatus()) && row.getClosesAt().isAfter(now)).findFirst().orElse(null));
    notifications.beforeWrite(() -> {
      firstInserted.countDown();
      await(allowCommit);
    });
    CheckinSessionService proxied = proxied(tx);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> proxied.open(7L, 11L, new OpenSessionRequest(15)));
      assertThat(firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> second = executor.submit(() -> proxied.open(8L, 11L, new OpenSessionRequest(15)));
      assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(second.isDone()).isFalse();
      allowCommit.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class)
          .satisfies(error -> assertThat(((BusinessException) error.getCause()).code()).isEqualTo("CHECKIN_SESSION_ALREADY_OPEN"));
      assertThat(inserted).hasSize(1);
      assertThat(tx.commits.get()).isEqualTo(1);
      assertThat(tx.rollbacks.get()).isEqualTo(1);
      assertThat(notifications.commands()).hasSize(2);
    } finally {
      allowCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void notificationFailureRollsBackOpenThroughActualSpringTransactionAdvice() {
    DatabaseTransactions tx = new DatabaseTransactions(inserted);
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> {
      tx.lockActivity();
      return activity;
    });
    notifications.beforeWrite(() -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue());
    RuntimeException failure = new IllegalStateException("Notification storage unavailable");
    notifications.failNextWrite(failure);
    assertThatThrownBy(() -> proxied(tx).open(7L, 11L, new OpenSessionRequest(15))).isSameAs(failure);
    assertThat(inserted).isEmpty();
    assertThat(tx.rollbacks.get()).isEqualTo(1);
    assertThat(tx.commits.get()).isZero();
    assertThat(notifications.commands()).isEmpty();
  }

  private void arrangeClose(CheckinSession row) {
    when(sessions.selectById(row.getId())).thenReturn(row);
    when(sessions.selectByIdForUpdate(row.getId(), 11L)).thenReturn(row);
  }

  private CheckinSession session(Long id, String status, LocalDateTime closesAt) {
    CheckinSession row = new CheckinSession();
    row.setId(id);
    row.setOrganizationId(10L);
    row.setActivityId(11L);
    row.setSessionType("STATIC_CODE");
    row.setStatus(status);
    row.setOpensAt(now.minusMinutes(5));
    row.setClosesAt(closesAt);
    row.setVersion(0);
    return row;
  }

  private void rejects(Runnable action, String code) {
    assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo(code);
  }

  private String sql(MybatisConfiguration config, String method, Map<String, Object> parameters) {
    BoundSql bound = config.getMappedStatement(CheckinSessionMapper.class.getName() + "." + method).getBoundSql(parameters);
    return bound.getSql().replaceAll("\\s+", " ").trim();
  }

  private CheckinSessionService proxied(DatabaseTransactions tx) {
    ProxyFactory factory = new ProxyFactory(service);
    factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
    return (CheckinSessionService) factory.getProxy();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for fixture");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  /** Test-only transactional row-lock model, not evidence of live MySQL behavior. */
  private static final class DatabaseTransactions extends AbstractPlatformTransactionManager {
    private final ReentrantLock activityRow = new ReentrantLock();
    private final List<CheckinSession> rows;
    private final ThreadLocal<Integer> originalSize = new ThreadLocal<>();
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();

    private DatabaseTransactions(List<CheckinSession> rows) { this.rows = rows; }

    private void lockActivity() {
      activityRow.lock();
      originalSize.set(rows.size());
    }

    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      rollbacks.incrementAndGet();
      if (originalSize.get() != null) rows.subList(originalSize.get(), rows.size()).clear();
    }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
      originalSize.remove();
      if (activityRow.isHeldByCurrentThread()) activityRow.unlock();
    }
  }
}
