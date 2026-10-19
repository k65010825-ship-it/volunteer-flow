package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.audit.AuditLogMapper;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.checkin.CheckinService.CheckinRequest;
import com.volunteerflow.checkin.CheckinService.ManualCheckinRequest;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.VerifiedCredential;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.registration.CapturingDomainEventPublisher;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.RegistrationCycle;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

class CheckinServiceTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final CheckinSessionMapper sessions = mock(CheckinSessionMapper.class);
  private final OrganizationMemberMapper members = mock(OrganizationMemberMapper.class);
  private final RegistrationCycleMapper cycles = mock(RegistrationCycleMapper.class);
  private final CheckinRecordMapper records = mock(CheckinRecordMapper.class);
  private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  private final AuditLogMapper auditLogs = mock(AuditLogMapper.class);
  private final CapturingDomainEventPublisher events = new CapturingDomainEventPublisher();
  private final List<CheckinRecord> stored = new ArrayList<>();
  private final List<AuditLog> logs = new ArrayList<>();
  private final CheckinCodeGenerator codes = new SecureCheckinCodeGenerator(new SecureRandom());
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 8, 0);
  private final CheckinRequest request = new CheckinRequest(11L, " abcd2345 ");
  private Activity activity;
  private CheckinSession session;
  private RegistrationCycle cycle;
  private CheckinService service;
  private final ManualCheckinRequest manual = new ManualCheckinRequest(201L, 301L, "  attendance corrected  ");

  @BeforeEach
  void setup() {
    activity = new Activity();
    activity.setId(11L); activity.setOrganizationId(10L); activity.setStatus("PUBLISHED");
    activity.setActivityEndAt(now.plusHours(1));
    session = new CheckinSession();
    session.setId(101L); session.setOrganizationId(10L); session.setActivityId(11L);
    session.setStatus("OPEN"); session.setSessionType("STATIC_CODE");
    session.setOpensAt(now.minusMinutes(1)); session.setClosesAt(now.plusMinutes(15));
    session.setStaticCodeHash(HexFormat.of().formatHex(codes.digest("ABCD2345")));
    cycle = new RegistrationCycle();
    cycle.setId(301L); cycle.setRegistrationId(201L); cycle.setOrganizationId(10L);
    cycle.setActivityId(11L); cycle.setPositionId(12L); cycle.setUserId(21L);
    cycle.setCycleNumber(1); cycle.setStatus("CONFIRMED"); cycle.setAttendanceStatus("PENDING"); cycle.setVersion(0);
    when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
    when(sessions.selectById(101L)).thenReturn(session);
    when(sessions.selectByIdForUpdate(101L, 11L)).thenReturn(session);
    OrganizationMember member = new OrganizationMember();
    member.setOrganizationId(10L); member.setUserId(21L); member.setStatus("ACTIVE");
    when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(member);
    when(cycles.selectEffectiveConfirmedForUpdate(10L, 11L, 21L)).thenReturn(cycle);
    when(records.insert(any(CheckinRecord.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0)); return 1;
    });
    when(cycles.markCheckedIn(301L, 10L, 11L, 21L, 0)).thenAnswer(invocation -> {
      cycle.setAttendanceStatus("CHECKED_IN"); cycle.setVersion(1); return 1;
    });
    when(auditLogs.insert(any(AuditLog.class))).thenAnswer(invocation -> {
      logs.add(invocation.getArgument(0)); return 1;
    });
    service = new CheckinService(activities, sessions, members, cycles, records, authorization,
        new AuditService(auditLogs, new ObjectMapper()), events, codes, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test
  void correctNormalizedCodeCreatesOneSelfRecordTransitionsAttendanceAndAuditsWithoutSecrets() throws Exception {
    events.beforePublish(() -> {
      assertThat(stored).hasSize(1);
      assertThat(logs).hasSize(1);
      assertThat(cycle.getAttendanceStatus()).isEqualTo("CHECKED_IN");
    });
    var result = service.selfCheckin(21L, 101L, request);
    assertThat(events.commands()).containsExactly(new DomainEventCommand(10L, 11L, "CHECKIN_RECORD",
        result.recordId().toString(), "checkin.completed.v1",
        new CheckinStatusPayload(21L, 101L, result.recordId(), "SELF_SERVICE")));
    assertThat(stored).hasSize(1);
    CheckinRecord row = stored.get(0);
    assertThat(row.getCheckinMethod()).isEqualTo("SELF_SERVICE");
    assertThat(row.getOrganizationId()).isEqualTo(10L);
    assertThat(row.getActivityId()).isEqualTo(11L);
    assertThat(row.getUserId()).isEqualTo(21L);
    assertThat(row.getCheckinSessionId()).isEqualTo(101L);
    assertThat(row.getRegistrationCycleId()).isEqualTo(301L);
    assertThat(row.getStatus()).isEqualTo("VALID");
    assertThat(row.getOperatorId()).isNull(); assertThat(row.getManualReason()).isNull();
    assertThat(row.getCheckedInAt()).isEqualTo(now);
    assertThat(result.recordId()).isEqualTo(row.getId());
    assertThat(result.activityId()).isEqualTo(11L); assertThat(result.userId()).isEqualTo(21L);
    assertThat(result.method()).isEqualTo("SELF_SERVICE"); assertThat(result.checkedInAt()).isEqualTo(now);
    assertThat(cycle.getAttendanceStatus()).isEqualTo("CHECKED_IN");
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0).getAction()).isEqualTo("checkin.self.created");
    assertThat(logs.get(0).getActorUserId()).isEqualTo(21L);
    assertThat(logs.get(0).getResourceId()).isEqualTo(row.getId());
    assertThat(logs.get(0).getReason()).isNull();
    String output = new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(row, result, logs));
    assertThat(output).doesNotContain("ABCD2345", "abcd2345", session.getStaticCodeHash());
    assertThat(request.toString()).doesNotContain("abcd2345");
  }

  @Test void wrongCodeDoesNotWrite() {
    rejects(() -> service.selfCheckin(21L, 101L, new CheckinRequest(11L, "WRONG234")), "INVALID_CHECKIN_CODE");
    unchanged();
  }

  @Test
  void dynamicSessionCannotUseStaticCodeEndpoint() {
    session.setSessionType("DYNAMIC_QR");
    session.setStaticCodeHash(null);

    rejects(() -> service.selfCheckin(21L, 101L, request), "INVALID_CHECKIN_CODE");

    unchanged();
  }

  @Test void exactExpiryBoundaryIsRejected() {
    session.setClosesAt(now);
    rejects(() -> service.selfCheckin(21L, 101L, request), "CHECKIN_SESSION_NOT_OPEN"); unchanged();
  }

  @Test void futureOpeningIsRejected() {
    session.setOpensAt(now.plusNanos(1));
    rejects(() -> service.selfCheckin(21L, 101L, request), "CHECKIN_SESSION_NOT_OPEN"); unchanged();
  }

  @ParameterizedTest @ValueSource(strings = {"CLOSED", "EXPIRED"})
  void closedSessionsAreRejected(String status) {
    session.setStatus(status);
    rejects(() -> service.selfCheckin(21L, 101L, request), "CHECKIN_SESSION_NOT_OPEN"); unchanged();
  }

  @Test void inactiveMemberCannotCheckIn() {
    when(members.selectActiveMemberForUpdate(21L, 10L)).thenReturn(null);
    rejects(() -> service.selfCheckin(21L, 101L, request), "ORGANIZATION_NOT_FOUND"); unchanged();
    verify(cycles, never()).selectEffectiveConfirmedForUpdate(anyLong(), anyLong(), anyLong());
  }

  @Test void nonConfirmedCycleIsRejected() {
    when(cycles.selectEffectiveConfirmedForUpdate(10L, 11L, 21L)).thenReturn(null);
    rejects(() -> service.selfCheckin(21L, 101L, request), "REGISTRATION_NOT_CONFIRMED"); unchanged();
  }

  @Test void crossActivitySessionIsRejected() {
    rejects(() -> service.selfCheckin(21L, 101L, new CheckinRequest(99L, "ABCD2345")), "CHECKIN_SESSION_NOT_FOUND"); unchanged();
  }

  @Test void crossOrganizationLockedSessionIsRejected() {
    session.setOrganizationId(999L);
    rejects(() -> service.selfCheckin(21L, 101L, request), "CHECKIN_SESSION_NOT_FOUND"); unchanged();
  }

  @Test void alreadyCheckedInIsRejectedWithoutSecondTransition() {
    cycle.setAttendanceStatus("CHECKED_IN");
    rejects(() -> service.selfCheckin(21L, 101L, request), "ALREADY_CHECKED_IN");
    assertThat(stored).isEmpty(); assertThat(logs).isEmpty();
  }

  @Test void existingRecordEvenForOtherCyclePreventsAnotherRecord() {
    when(records.selectByActivityAndUserForUpdate(11L, 21L)).thenReturn(new CheckinRecord());
    rejects(() -> service.selfCheckin(21L, 101L, request), "ALREADY_CHECKED_IN"); unchanged();
  }

  @ParameterizedTest @ValueSource(strings = {"FINISHED", "CANCELED"})
  void finalActivityCannotSelfCheckIn(String status) {
    activity.setStatus(status);
    rejects(() -> service.selfCheckin(21L, 101L, request), "ACTIVITY_FINAL"); unchanged();
  }

  @Test void exactActivityEndIsRejected() {
    activity.setActivityEndAt(now);
    rejects(() -> service.selfCheckin(21L, 101L, request), "ACTIVITY_FINAL"); unchanged();
  }

  @Test void lockOrderIsActivitySessionMembershipEffectiveCycleThenRecord() {
    service.selfCheckin(21L, 101L, request);
    var order = inOrder(activities, sessions, members, cycles, records);
    order.verify(activities).selectByIdForUpdate(11L);
    order.verify(sessions).selectByIdForUpdate(101L, 11L);
    order.verify(members).selectActiveMemberForUpdate(21L, 10L);
    order.verify(cycles).selectEffectiveConfirmedForUpdate(10L, 11L, 21L);
    order.verify(records).selectByActivityAndUserForUpdate(11L, 21L);
    order.verify(records).insert(any(CheckinRecord.class));
    order.verify(cycles).markCheckedIn(301L, 10L, 11L, 21L, 0);
  }

  @Test void onlyActivityUserDatabaseDuplicateMapsToAlreadyCheckedIn() {
    when(records.insert(any(CheckinRecord.class))).thenThrow(new DuplicateKeyException("insert failed",
        new SQLException("Duplicate entry '11-21' for key 'checkin_record.uk_checkin_record_activity_user'", "23000", 1062)));
    rejects(() -> service.selfCheckin(21L, 101L, request), "ALREADY_CHECKED_IN"); unchanged();
  }

  @Test
  void expiredProofAfterLockWaitCreatesNoRecord() {
    session.setSessionType("DYNAMIC_QR");
    Instant expiry = now.toInstant(ZoneOffset.UTC);
    var expired = new VerifiedCredential(101L,
        now.minusSeconds(60).toEpochSecond(ZoneOffset.UTC), expiry);
    Clock lockWaitClock = mock(Clock.class);
    when(lockWaitClock.instant()).thenReturn(expiry);
    CheckinService delayed = new CheckinService(activities, sessions, members, cycles, records,
        authorization, new AuditService(auditLogs, new ObjectMapper()), events, codes, lockWaitClock);

    rejects(() -> delayed.dynamicSelfCheckin(21L, 101L, 11L, expired),
        "INVALID_DYNAMIC_CHECKIN_CODE");

    unchanged();
    var order = inOrder(records, lockWaitClock);
    order.verify(records).selectByActivityAndUserForUpdate(11L, 21L);
    order.verify(lockWaitClock).instant();
  }

  @Test
  void proofForAnotherSessionIsRejected() {
    session.setSessionType("DYNAMIC_QR");
    var otherSession = new VerifiedCredential(102L, now.toEpochSecond(ZoneOffset.UTC),
        now.plusMinutes(1).toInstant(ZoneOffset.UTC));

    rejects(() -> service.dynamicSelfCheckin(21L, 101L, 11L, otherSession),
        "INVALID_DYNAMIC_CHECKIN_CODE");

    unchanged();
  }

  @Test
  void duplicateDynamicCheckinMapsToAlreadyCheckedIn() {
    session.setSessionType("DYNAMIC_QR");
    var proof = new VerifiedCredential(101L, now.toEpochSecond(ZoneOffset.UTC),
        now.plusMinutes(1).toInstant(ZoneOffset.UTC));
    when(records.insert(any(CheckinRecord.class))).thenThrow(new DuplicateKeyException("insert failed",
        new SQLException("Duplicate entry '11-21' for key 'uk_checkin_record_activity_user'", "23000", 1062)));

    rejects(() -> service.dynamicSelfCheckin(21L, 101L, 11L, proof), "ALREADY_CHECKED_IN");

    unchanged();
  }

  @Test void unrelatedDuplicateIsNotMasked() {
    DuplicateKeyException failure = new DuplicateKeyException("insert failed",
        new SQLException("Duplicate entry '123' for key 'PRIMARY'", "23000", 1062));
    when(records.insert(any(CheckinRecord.class))).thenThrow(failure);
    assertThatThrownBy(() -> service.selfCheckin(21L, 101L, request)).isSameAs(failure); unchanged();
  }

  @Test void zeroRowRecordInsertFailsWithoutAttendanceOrAudit() {
    when(records.insert(any(CheckinRecord.class))).thenReturn(0);
    assertThatThrownBy(() -> service.selfCheckin(21L, 101L, request)).isInstanceOf(OptimisticLockingFailureException.class);
    unchanged();
  }

  @Test void zeroRowAttendanceUpdateFailsWithoutAudit() {
    doReturn(0).when(cycles).markCheckedIn(301L, 10L, 11L, 21L, 0);
    assertThatThrownBy(() -> service.selfCheckin(21L, 101L, request)).isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(logs).isEmpty();
  }

  @Test void queryContractsKeepTenantEffectiveCycleAndCompareAndSetGuards() {
    MybatisConfiguration config = new MybatisConfiguration();
    config.addMapper(RegistrationCycleMapper.class); config.addMapper(CheckinRecordMapper.class);
    String confirmed = sql(config, RegistrationCycleMapper.class, "selectEffectiveConfirmedForUpdate");
    assertThat(confirmed).contains("organization_id=?", "activity_id=?", "user_id=?", "status='CONFIRMED'", "NOT EXISTS", "FOR UPDATE");
    String transition = sql(config, RegistrationCycleMapper.class, "markCheckedIn");
    assertThat(transition).contains("attendance_status='CHECKED_IN'", "version=version+1", "status='CONFIRMED'", "version=?", "attendance_status IN ('PENDING','NO_SHOW')", "organization_id=?", "activity_id=?", "user_id=?");
    assertThat(sql(config, CheckinRecordMapper.class, "selectByActivityAndUserForUpdate"))
        .contains("activity_id=?", "user_id=?", "FOR UPDATE");
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n"})
  void manualRequiresNonBlankReason(String reason) {
    arrangeManual();
    rejects(() -> service.manualCheckin(7L, 101L, new ManualCheckinRequest(201L, 301L, reason)), "INVALID_MANUAL_CHECKIN_REASON"); unchanged();
  }

  @Test void manualRejects501CharactersAfterTrimming() {
    arrangeManual();
    rejects(() -> service.manualCheckin(7L, 101L, new ManualCheckinRequest(201L, 301L, "x".repeat(501))), "INVALID_MANUAL_CHECKIN_REASON"); unchanged();
  }

  @ParameterizedTest @ValueSource(ints = {1, 500})
  void manualAcceptsInclusiveTrimmedReasonBounds(int length) {
    arrangeManual();
    service.manualCheckin(7L, 101L, new ManualCheckinRequest(201L, 301L, "  " + "x".repeat(length) + "  "));
    assertThat(stored.get(0).getManualReason()).hasSize(length);
  }

  @Test void manualMissingPermissionCannotWrite() {
    arrangeManual();
    doThrow(new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Denied"))
        .when(authorization).requirePermission(7L, 10L, "checkin:manage");
    rejects(() -> service.manualCheckin(7L, 101L, manual), "PERMISSION_DENIED"); unchanged();
  }

  @Test void manualInactiveActorCannotWrite() {
    arrangeManual(); when(members.selectActiveMemberForUpdate(7L, 10L)).thenReturn(null);
    rejects(() -> service.manualCheckin(7L, 101L, manual), "ORGANIZATION_NOT_FOUND"); unchanged();
  }

  @Test void manualRejectsUnrelatedRegistration() {
    arrangeManual();
    rejects(() -> service.manualCheckin(7L, 101L, new ManualCheckinRequest(999L, 301L, "correction")), "REGISTRATION_NOT_FOUND"); unchanged();
  }

  @Test void manualRejectsCycleFromAnotherActivity() {
    arrangeManual(); cycle.setActivityId(99L);
    rejects(() -> service.manualCheckin(7L, 101L, manual), "REGISTRATION_NOT_FOUND"); unchanged();
  }

  @ParameterizedTest @ValueSource(strings = {"WAITLISTED", "PENDING_REVIEW", "CANCELED", "LATE_CANCELED"})
  void manualRequiresCycleThatRemainsConfirmed(String status) {
    arrangeManual(); cycle.setStatus(status);
    when(cycles.selectEffectiveConfirmedForUpdate(10L, 11L, 21L)).thenReturn(null);
    rejects(() -> service.manualCheckin(7L, 101L, manual), "REGISTRATION_NOT_CONFIRMED"); unchanged();
  }

  @Test void manualRejectsStaleConfirmedCycleWhenAnotherCycleIsEffective() {
    arrangeManual();
    RegistrationCycle newer = new RegistrationCycle();
    newer.setId(302L); newer.setRegistrationId(201L); newer.setOrganizationId(10L);
    newer.setActivityId(11L); newer.setUserId(21L); newer.setStatus("CONFIRMED");
    when(cycles.selectEffectiveConfirmedForUpdate(10L, 11L, 21L)).thenReturn(newer);
    rejects(() -> service.manualCheckin(7L, 101L, manual), "REGISTRATION_NOT_CONFIRMED"); unchanged();
  }

  @ParameterizedTest @ValueSource(strings = {"CLOSED", "EXPIRED"})
  void manualCorrectsNoShowAfterActivityAndSessionEnd(String status) throws Exception {
    arrangeManual();
    session.setStatus(status); session.setClosesAt(now.minusHours(1));
    activity.setStatus("FINISHED"); activity.setActivityEndAt(now.minusHours(1));
    cycle.setAttendanceStatus("NO_SHOW");
    var result = service.manualCheckin(7L, 101L, manual);
    assertThat(stored).hasSize(1); assertThat(cycle.getAttendanceStatus()).isEqualTo("CHECKED_IN");
    CheckinRecord row = stored.get(0);
    assertThat(row.getCheckinMethod()).isEqualTo("MANUAL");
    assertThat(row.getOperatorId()).isEqualTo(7L); assertThat(row.getManualReason()).isEqualTo("attendance corrected");
    assertThat(row.getRegistrationCycleId()).isEqualTo(301L); assertThat(row.getUserId()).isEqualTo(21L);
    assertThat(result.method()).isEqualTo("MANUAL");
    assertThat(events.commands()).containsExactly(new DomainEventCommand(10L, 11L, "CHECKIN_RECORD",
        result.recordId().toString(), "checkin.corrected.v1",
        new CheckinStatusPayload(21L, 101L, result.recordId(), "MANUAL")));
    assertThat(logs).hasSize(1); assertThat(logs.get(0).getAction()).isEqualTo("checkin.manual.created");
    assertThat(logs.get(0).getActorUserId()).isEqualTo(7L); assertThat(logs.get(0).getReason()).isEqualTo("attendance corrected");
    assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(logs)).doesNotContain("ABCD2345", session.getStaticCodeHash());
  }

  @Test void manualLockOrderMatchesSelf() {
    arrangeManual(); service.manualCheckin(7L, 101L, manual);
    var order = inOrder(activities, sessions, members, authorization, cycles, records);
    order.verify(activities).selectByIdForUpdate(11L); order.verify(sessions).selectByIdForUpdate(101L, 11L);
    order.verify(members).selectActiveMemberForUpdate(7L, 10L);
    order.verify(authorization).requirePermission(7L, 10L, "checkin:manage");
    order.verify(cycles).selectEffectiveConfirmedForUpdate(10L, 11L, 21L);
    order.verify(records).selectByActivityAndUserForUpdate(11L, 21L);
  }

  @Test void manualUniqueKeyConflictMapsWithoutAuditOrAttendanceChange() {
    arrangeManual();
    when(records.insert(any(CheckinRecord.class))).thenThrow(new DuplicateKeyException("insert failed",
        new SQLException("Duplicate entry '11-21' for key 'uk_checkin_record_activity_user'", "23000", 1062)));
    rejects(() -> service.manualCheckin(7L, 101L, manual), "ALREADY_CHECKED_IN"); unchanged();
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void proxiedSelfManualRaceHasOneRecordOneTransitionAndOneAuditWhicheverWins(boolean selfWins) throws Exception {
    arrangeManual(); DatabaseTransactions tx = new DatabaseTransactions();
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch secondAttempt = new CountDownLatch(1), firstAudited = new CountDownLatch(1), release = new CountDownLatch(1);
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> {
      if (attempts.incrementAndGet() == 2) secondAttempt.countDown(); tx.lockActivity(); return activity;
    });
    when(records.selectByActivityAndUserForUpdate(11L, 21L)).thenAnswer(invocation -> stored.isEmpty() ? null : stored.get(0));
    doAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      logs.add(invocation.getArgument(0)); firstAudited.countDown(); await(release); return 1;
    }).when(auditLogs).insert(any(AuditLog.class));
    CheckinService proxied = proxied(tx); ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> selfWins ? proxied.selfCheckin(21L, 101L, request) : proxied.manualCheckin(7L, 101L, manual));
      assertThat(firstAudited.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> second = executor.submit(() -> selfWins ? proxied.manualCheckin(7L, 101L, manual) : proxied.selfCheckin(21L, 101L, request));
      assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(second.isDone()).isFalse();
      release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
      assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
          .satisfies(error -> assertThat(((BusinessException) error.getCause()).code()).isEqualTo("ALREADY_CHECKED_IN"));
      assertThat(stored).hasSize(1); assertThat(stored.get(0).getCheckinMethod()).isEqualTo(selfWins ? "SELF_SERVICE" : "MANUAL");
      assertThat(cycle.getAttendanceStatus()).isEqualTo("CHECKED_IN"); assertThat(cycle.getVersion()).isEqualTo(1);
      assertThat(logs).hasSize(1); assertThat(tx.commits.get()).isEqualTo(1); assertThat(tx.rollbacks.get()).isEqualTo(1);
      verify(cycles, times(1)).markCheckedIn(301L, 10L, 11L, 21L, 0);
    } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void auditFailureRollsBackRecordAndAttendanceThroughSpringTransactionAdvice(boolean self) {
    arrangeManual(); DatabaseTransactions tx = new DatabaseTransactions();
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> { tx.lockActivity(); return activity; });
    RuntimeException failure = new IllegalStateException("Audit storage unavailable");
    doThrow(failure).when(auditLogs).insert(any(AuditLog.class)); CheckinService proxied = proxied(tx);
    assertThatThrownBy(() -> { if (self) proxied.selfCheckin(21L, 101L, request); else proxied.manualCheckin(7L, 101L, manual); }).isSameAs(failure);
    unchanged(); assertThat(cycle.getVersion()).isZero(); assertThat(tx.rollbacks.get()).isEqualTo(1); assertThat(tx.commits.get()).isZero();
  }

  @Test void zeroRowTransitionRollsBackInsertedRecord() {
    DatabaseTransactions tx = new DatabaseTransactions();
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> { tx.lockActivity(); return activity; });
    doReturn(0).when(cycles).markCheckedIn(301L, 10L, 11L, 21L, 0);
    assertThatThrownBy(() -> proxied(tx).selfCheckin(21L, 101L, request)).isInstanceOf(OptimisticLockingFailureException.class);
    unchanged(); assertThat(tx.rollbacks.get()).isEqualTo(1);
  }

  private void arrangeManual() {
    when(cycles.selectById(301L)).thenReturn(cycle);
    OrganizationMember actor = new OrganizationMember();
    actor.setOrganizationId(10L); actor.setUserId(7L); actor.setStatus("ACTIVE");
    when(members.selectActiveMemberForUpdate(7L, 10L)).thenReturn(actor);
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void zeroRowAuditIsNotReportedAsSuccessfulCheckin(boolean self) {
    arrangeManual(); DatabaseTransactions tx = new DatabaseTransactions();
    when(activities.selectByIdForUpdate(11L)).thenAnswer(invocation -> { tx.lockActivity(); return activity; });
    doReturn(0).when(auditLogs).insert(any(AuditLog.class));
    CheckinService proxied = proxied(tx);
    assertThatThrownBy(() -> { if (self) proxied.selfCheckin(21L, 101L, request); else proxied.manualCheckin(7L, 101L, manual); })
        .isInstanceOf(OptimisticLockingFailureException.class);
    unchanged(); assertThat(tx.rollbacks.get()).isEqualTo(1);
  }

  @Test void selfCannotCorrectNoShow() {
    cycle.setAttendanceStatus("NO_SHOW");
    rejects(() -> service.selfCheckin(21L, 101L, request), "ATTENDANCE_NOT_PENDING");
    assertThat(stored).isEmpty(); assertThat(logs).isEmpty(); assertThat(cycle.getAttendanceStatus()).isEqualTo("NO_SHOW");
  }

  @Test void manualCannotCheckInAttendanceThatWasNeverRequired() {
    arrangeManual(); cycle.setAttendanceStatus("NOT_REQUIRED");
    rejects(() -> service.manualCheckin(7L, 101L, manual), "ATTENDANCE_NOT_PENDING");
    assertThat(stored).isEmpty(); assertThat(logs).isEmpty();
  }

  @Test void missingRequestsAreRejectedWithoutWrites() {
    rejects(() -> service.selfCheckin(21L, 101L, null), "INVALID_CHECKIN_REQUEST");
    rejects(() -> service.manualCheckin(7L, 101L, null), "INVALID_MANUAL_CHECKIN_REQUEST"); unchanged();
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
  void missingCodeDoesNotWrite(String code) {
    rejects(() -> service.selfCheckin(21L, 101L, new CheckinRequest(11L, code)), "INVALID_CHECKIN_CODE"); unchanged();
  }

  @Test void missingSessionIsRejectedForBothMethods() {
    arrangeManual();
    rejects(() -> service.selfCheckin(21L, 999L, request), "CHECKIN_SESSION_NOT_FOUND");
    rejects(() -> service.manualCheckin(7L, 999L, manual), "CHECKIN_SESSION_NOT_FOUND"); unchanged();
  }

  @Test void manualDoesNotMaskPrimaryKeyConflict() {
    arrangeManual(); DuplicateKeyException failure = new DuplicateKeyException("insert failed",
        new SQLException("Duplicate entry '123' for key 'PRIMARY'", "23000", 1062));
    when(records.insert(any(CheckinRecord.class))).thenThrow(failure);
    assertThatThrownBy(() -> service.manualCheckin(7L, 101L, manual)).isSameAs(failure); unchanged();
  }

  @Test
  void dynamicCheckinEmitsSameCompletionFactWithoutCredential() {
    session.setSessionType("DYNAMIC_QR");
    var proof = new VerifiedCredential(101L, now.toEpochSecond(ZoneOffset.UTC),
        now.plusMinutes(1).toInstant(ZoneOffset.UTC));
    var result = service.dynamicSelfCheckin(21L, 101L, 11L, proof);
    assertThat(events.commands()).containsExactly(new DomainEventCommand(10L, 11L, "CHECKIN_RECORD",
        result.recordId().toString(), "checkin.completed.v1",
        new CheckinStatusPayload(21L, 101L, result.recordId(), "SELF_SERVICE")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"SELF_SERVICE", "MANUAL"})
  void outboxFailureRollsBackRecordAttendanceAndAudit(String method) {
    if ("MANUAL".equals(method)) arrangeManual();
    DatabaseTransactions tx = new DatabaseTransactions();
    when(activities.selectByIdForUpdate(11L)).thenAnswer(call -> { tx.lockActivity(); return activity; });
    events.beforePublish(() -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(stored).hasSize(1);
      assertThat(logs).hasSize(1);
      assertThat(cycle.getAttendanceStatus()).isEqualTo("CHECKED_IN");
    });
    RuntimeException failure = new DuplicateKeyException("Outbox insert failed");
    events.failNextPublish(failure);
    assertThatThrownBy(() -> {
      if ("MANUAL".equals(method)) proxied(tx).manualCheckin(7L, 101L, manual);
      else proxied(tx).selfCheckin(21L, 101L, request);
    }).isSameAs(failure);
    assertThat(tx.commits.get()).isZero();
    assertThat(tx.rollbacks.get()).isEqualTo(1);
    unchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"SELF_SERVICE", "DYNAMIC_QR", "MANUAL"})
  void realPublisherCommitsAttendanceAuditAndOnlyNonNotificationFact(String mode) {
    BusinessOutboxFixture f = new BusinessOutboxFixture(
        Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneId.of("Asia/Shanghai")));
    transactionalCheckin(f, mode).run();
    assertThat(f.commits).isEqualTo(1);
    assertThat(f.rollbacks).isZero();
    assertThat(f.committed).anyMatch(CheckinRecord.class::isInstance)
        .anyMatch(RegistrationCycle.class::isInstance).anyMatch(AuditLog.class::isInstance);
    assertThat(f.rows()).singleElement().satisfies(row -> {
      assertThat(row.getTopic()).isEqualTo("volunteerflow.checkin-events.v1");
      assertThat(row.getMessageKey()).isEqualTo("10:11");
      assertThat(row.getStatus()).isEqualTo("PENDING");
      var event = f.codec.decode(row.getPayloadJson());
      assertThat(event.organizationId()).isEqualTo(10L);
      assertThat(event.activityId()).isEqualTo(11L);
      assertThat(event.aggregateType()).isEqualTo("CHECKIN_RECORD");
      assertThat(event.aggregateId()).isEqualTo(stored.get(0).getId().toString());
      assertThat(event.eventType()).isEqualTo("MANUAL".equals(mode) ? "checkin.corrected.v1" : "checkin.completed.v1");
      assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-10T08:00:00Z"));
      assertThat(event.payload()).isEqualTo(new CheckinStatusPayload(21L, 101L,
          stored.get(0).getId(), "MANUAL".equals(mode) ? "MANUAL" : "SELF_SERVICE"));
      assertThat(row.getPayloadJson()).doesNotContain("notificationType", "title", "content",
          "staticCodeHash", "ABCD2345", "abcd2345", "expiresAt", "token", session.getStaticCodeHash());
    });
    verifyNoInteractions(f.unavailableKafka);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SELF_SERVICE", "DYNAMIC_QR", "MANUAL"})
  void realOutboxFailureRollsBackCheckinAuditAndAttendance(String mode) {
    BusinessOutboxFixture f = new BusinessOutboxFixture(Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    f.failAtInsert = 1;
    Runnable action = transactionalCheckin(f, mode);
    assertThatThrownBy(action::run).isSameAs(f.failure);
    assertThat(f.rollbacks).isEqualTo(1);
    assertThat(f.commits).isZero();
    assertThat(f.committed).isEmpty();
    assertThat(f.staged).isEmpty();
    assertThat(stored).isEmpty();
    assertThat(cycle.getAttendanceStatus()).isEqualTo("PENDING");
    assertThat(cycle.getVersion()).isZero();
    verifyNoInteractions(f.unavailableKafka);
  }

  private Runnable transactionalCheckin(BusinessOutboxFixture f, String mode) {
    doAnswer(call -> {
      stored.add(call.getArgument(0)); return f.stage(call.getArgument(0));
    }).when(records).insert(any(CheckinRecord.class));
    doAnswer(call -> {
      cycle.setAttendanceStatus("CHECKED_IN"); cycle.setVersion(1); return f.stage(cycle);
    }).when(cycles).markCheckedIn(301L, 10L, 11L, 21L, 0);
    f.afterRollback = () -> { stored.clear(); cycle.setAttendanceStatus("PENDING"); cycle.setVersion(0); };
    CheckinService proxied = f.proxy(new CheckinService(activities, sessions, members, cycles,
        records, authorization, f.audit, f.publisher, codes, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    if ("MANUAL".equals(mode)) {
      arrangeManual();
      return () -> proxied.manualCheckin(7L, 101L, manual);
    }
    if ("DYNAMIC_QR".equals(mode)) {
      session.setSessionType("DYNAMIC_QR");
      return () -> proxied.dynamicSelfCheckin(21L, 101L, 11L, new VerifiedCredential(101L,
          now.toEpochSecond(ZoneOffset.UTC), now.plusMinutes(1).toInstant(ZoneOffset.UTC)));
    }
    return () -> proxied.selfCheckin(21L, 101L, request);
  }

  private CheckinService proxied(DatabaseTransactions tx) {
    ProxyFactory factory = new ProxyFactory(service);
    factory.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
    return (CheckinService) factory.getProxy();
  }

  private static void await(CountDownLatch latch) {
    try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for fixture"); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
  }

  /** Test-only transaction/row-lock model; not a live MySQL concurrency claim. */
  private final class DatabaseTransactions extends AbstractPlatformTransactionManager {
    private final ReentrantLock activityRow = new ReentrantLock();
    private final ThreadLocal<Snapshot> snapshots = new ThreadLocal<>();
    private final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger();
    private void lockActivity() {
      activityRow.lock(); snapshots.set(new Snapshot(stored.size(), logs.size(), cycle.getAttendanceStatus(), cycle.getVersion()));
    }
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) { commits.incrementAndGet(); }
    @Override protected void doRollback(DefaultTransactionStatus status) {
      rollbacks.incrementAndGet(); Snapshot snapshot = snapshots.get();
      if (snapshot != null) {
        stored.subList(snapshot.records(), stored.size()).clear(); logs.subList(snapshot.audits(), logs.size()).clear();
        cycle.setAttendanceStatus(snapshot.attendance()); cycle.setVersion(snapshot.version());
      }
    }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
      snapshots.remove(); if (activityRow.isHeldByCurrentThread()) activityRow.unlock();
    }
  }
  private record Snapshot(int records, int audits, String attendance, int version) {}

  private String sql(MybatisConfiguration config, Class<?> mapper, String method) {
    return config.getMappedStatement(mapper.getName() + "." + method).getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ").trim();
  }

  private void unchanged() {
    assertThat(stored).isEmpty(); assertThat(logs).isEmpty();
    assertThat(events.commands()).isEmpty();
    assertThat(cycle.getAttendanceStatus()).isEqualTo("PENDING");
  }

  private void rejects(Runnable action, String code) {
    assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code()).isEqualTo(code);
  }
}
