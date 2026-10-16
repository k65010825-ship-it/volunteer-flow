package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.checkin.CheckinSessionService.SessionView;
import com.volunteerflow.checkin.CheckinViews.*;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class AttendanceQueryServiceTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final AttendanceMapper projections = mock(AttendanceMapper.class);
  private final OrganizationAuthorizationService authorization = mock(OrganizationAuthorizationService.class);
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 8, 0);
  private AttendanceQueryService service;
  private Activity activity;

  @BeforeEach
  void setup() {
    activity = new Activity();
    activity.setId(11L); activity.setOrganizationId(10L); activity.setStatus("PUBLISHED");
    activity.setActivityEndAt(now.plusHours(1));
    when(activities.selectById(11L)).thenReturn(activity);
    service = new AttendanceQueryService(activities, projections, authorization,
        Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test
  void currentReturnsEffectiveSessionAndOnlyPrincipalAttendanceWithoutSecrets() {
    var session = new SessionView(101L, 11L, "STATIC_CODE", "OPEN", now.minusMinutes(1), now.plusMinutes(15));
    var own = new OwnAttendance(201L, 301L, 12L, "Reception", "CHECKED_IN", 401L, now);
    when(projections.selectCurrentSession(10L, 11L, now)).thenReturn(session);
    when(projections.selectOwnAttendance(10L, 11L, 21L)).thenReturn(own);
    var view = service.current(21L, 11L);
    assertThat(view.session()).isEqualTo(session);
    assertThat(view.attendance()).isEqualTo(own);
    verify(authorization).requireMembership(21L, 10L);
    verify(projections).selectOwnAttendance(10L, 11L, 21L);
  }

  @Test
  void currentWithoutSessionStillRestoresOwnCheckedInStatus() {
    when(projections.selectOwnAttendance(10L, 11L, 21L))
        .thenReturn(new OwnAttendance(201L, 301L, 12L, "Reception", "CHECKED_IN", 401L, now));
    var view = service.current(21L, 11L);
    assertThat(view.session()).isNull();
    assertThat(view.attendance().attendanceStatus()).isEqualTo("CHECKED_IN");
  }

  @Test
  void memberWithoutEffectiveConfirmedCycleGetsNoPersonalAttendance() {
    assertThat(service.current(21L, 11L).attendance()).isNull();
  }

  @ParameterizedTest @ValueSource(strings = {"FINISHED", "CANCELED", "DRAFT"})
  void nonPublishedActivityHasNoEffectiveSession(String status) {
    activity.setStatus(status);
    assertThat(service.current(21L, 11L).session()).isNull();
    verify(projections, never()).selectCurrentSession(anyLong(), anyLong(), any());
  }

  @Test
  void exactActivityEndBoundaryHasNoEffectiveSession() {
    activity.setActivityEndAt(now);
    assertThat(service.current(21L, 11L).session()).isNull();
    verify(projections, never()).selectCurrentSession(anyLong(), anyLong(), any());
  }

  @Test
  void summaryUsesHandDerivedTwoPositionTotalsAndDoesNotCountPendingAsNoShow() {
    // Reception: checked-in, no-show, pending. Logistics: checked-in, pending.
    // Canceled cycle and an old CONFIRMED cycle superseded by CANCELED contribute zero.
    when(projections.selectTotals(10L, 11L)).thenReturn(new AttendanceCounts(5L, 2L, 1L, 2L));
    when(projections.selectPositions(10L, 11L)).thenReturn(List.of(
        new PositionCounts(12L, "Reception", 3L, 1L, 1L, 1L),
        new PositionCounts(13L, "Logistics", 2L, 1L, 0L, 1L)));
    var view = service.summary(21L, 11L);
    assertThat(view.confirmedCount()).isEqualTo(5);
    assertThat(view.checkedInCount()).isEqualTo(2);
    assertThat(view.noShowCount()).isEqualTo(1);
    assertThat(view.pendingCount()).isEqualTo(2);
    assertThat(view.checkinRate()).isEqualByComparingTo("0.4");
    assertThat(view.finalStatistics()).isFalse();
    assertThat(view.positions()).hasSize(2);
    assertThat(view.positions().get(0).checkinRate()).isEqualByComparingTo("0.3333");
    assertThat(view.positions().get(1).checkinRate()).isEqualByComparingTo("0.5");
    verify(authorization).requirePermission(21L, 10L, "checkin:manage");
    verify(projections).selectTotals(10L, 11L);
    verify(projections).selectPositions(10L, 11L);
  }

  @Test
  void zeroDenominatorIsExactlyZeroIncludingEmptyPosition() {
    when(projections.selectTotals(10L, 11L)).thenReturn(new AttendanceCounts(0L, 0L, 0L, 0L));
    when(projections.selectPositions(10L, 11L)).thenReturn(List.of(new PositionCounts(12L, "Empty", 0L, 0L, 0L, 0L)));
    var view = service.summary(21L, 11L);
    assertThat(view.checkinRate()).isEqualTo(BigDecimal.ZERO);
    assertThat(view.positions().get(0).checkinRate()).isEqualTo(BigDecimal.ZERO);
  }

  @ParameterizedTest @ValueSource(strings = {"FINISHED", "PUBLISHED", "CANCELED", "DRAFT"})
  void onlyFinishedStatisticsAreFinal(String status) {
    activity.setStatus(status);
    when(projections.selectTotals(10L, 11L)).thenReturn(new AttendanceCounts(0L, 0L, 0L, 0L));
    when(projections.selectPositions(10L, 11L)).thenReturn(List.of());
    assertThat(service.summary(21L, 11L).finalStatistics()).isEqualTo("FINISHED".equals(status));
  }

  @Test
  void missingActivityReturns404BeforeReadingAnyAttendance() {
    when(activities.selectById(11L)).thenReturn(null);
    rejects(() -> service.current(21L, 11L), HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");
    rejects(() -> service.summary(21L, 11L), HttpStatus.NOT_FOUND, "ACTIVITY_NOT_FOUND");
    verifyNoInteractions(authorization, projections);
  }

  @Test
  void crossOrganizationMemberCannotReadSessionOrAttendance() {
    doThrow(new BusinessException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "hidden"))
        .when(authorization).requireMembership(21L, 10L);
    rejects(() -> service.current(21L, 11L), HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND");
    verifyNoInteractions(projections);
  }

  @Test
  void memberWithoutManagementPermissionCannotReadSummary() {
    doThrow(new BusinessException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "denied"))
        .when(authorization).requirePermission(21L, 10L, "checkin:manage");
    rejects(() -> service.summary(21L, 11L), HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
    verifyNoInteractions(projections);
  }

  @Test
  void selfHttpAdapterDerivesActivityFromScopedSessionAndMembership() {
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 10L));
    var request = service.selfRequest(21L, 101L, "ABCD2345");
    assertThat(request).isEqualTo(new CheckinService.CheckinRequest(11L, "ABCD2345"));
    verify(authorization).requireMembership(21L, 10L);
  }

  @Test
  void dynamicSelfRequestRequiresMembershipWithoutCarryingToken() {
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 10L));

    var target = service.dynamicSelfRequest(21L, 101L);

    assertThat(target).isEqualTo(new AttendanceQueryService.DynamicCheckinTarget(11L));
    assertThat(target.toString()).doesNotContain("token");
    verify(authorization).requireMembership(21L, 10L);
  }

  @Test
  void missingOrInconsistentSessionRoutingIsHidden() {
    rejects(() -> service.selfRequest(21L, 101L, "ABCD2345"), HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND");
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 99L));
    rejects(() -> service.selfRequest(21L, 101L, "ABCD2345"), HttpStatus.NOT_FOUND, "CHECKIN_SESSION_NOT_FOUND");
  }

  @Test
  void manualHttpAdapterResolvesCurrentCycleAfterManagerAuthorization() {
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 10L));
    when(projections.selectRegistrationTarget(10L, 11L, 201L))
        .thenReturn(new RegistrationTarget(302L, "CONFIRMED"));
    var request = service.manualRequest(21L, 101L, 201L, "present");
    assertThat(request).isEqualTo(new CheckinService.ManualCheckinRequest(201L, 302L, "present"));
    var order = inOrder(authorization, projections);
    order.verify(authorization).requirePermission(21L, 10L, "checkin:manage");
    order.verify(projections).selectRegistrationTarget(10L, 11L, 201L);
  }

  @Test
  void missingOrCrossActivityManualTargetIs404() {
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 10L));
    rejects(() -> service.manualRequest(21L, 101L, 201L, "present"), HttpStatus.NOT_FOUND, "REGISTRATION_NOT_FOUND");
  }

  @ParameterizedTest @ValueSource(strings = {"CANCELED", "WAITLISTED", "PENDING_REVIEW", "REJECTED"})
  void manualAdapterNeverResurrectsNonConfirmedLatestCycle(String status) {
    when(projections.selectSessionReference(101L)).thenReturn(new SessionReference(11L, 10L));
    when(projections.selectRegistrationTarget(10L, 11L, 201L)).thenReturn(new RegistrationTarget(302L, status));
    rejects(() -> service.manualRequest(21L, 101L, 201L, "present"), HttpStatus.CONFLICT, "REGISTRATION_NOT_CONFIRMED");
  }

  @Test
  void totalsAndPositionsEmitScopedSqlAggregationWithLatestCycleAntiJoin() {
    var totals = bound("selectTotals");
    var positions = bound("selectPositions");
    for (var query : List.of(totals, positions)) {
      String sql = normalize(query.getSql());
      assertThat(sql).contains("COUNT(c.id)", "SUM(CASE WHEN", "c.status='CONFIRMED'", "NOT EXISTS",
          "newer.registration_id=c.registration_id", "newer.cycle_number > c.cycle_number");
      assertThat(query.getParameterMappings()).extracting(p -> p.getProperty())
          .contains("organizationId", "activityId");
    }
    assertThat(normalize(positions.getSql())).contains("LEFT JOIN", "GROUP BY p.id,p.name", "ORDER BY p.id",
        "p.organization_id=?", "p.activity_id=?", "c.organization_id=p.organization_id", "c.activity_id=p.activity_id");
  }

  @Test
  void sessionAndOwnQueriesEmitOnlySafeScopedProjectionsAndStrictTimeBoundary() {
    String session = normalize(bound("selectCurrentSession").getSql());
    assertThat(session).contains("organization_id=?", "activity_id=?", "status='OPEN'", "opens_at <= ?", "closes_at > ?")
        .doesNotContain("static_code_hash", "SELECT *");
    String own = normalize(bound("selectOwnAttendance").getSql());
    assertThat(own).contains("c.user_id=?", "c.organization_id=?", "c.activity_id=?", "c.status='CONFIRMED'",
        "NOT EXISTS", "r.organization_id=c.organization_id", "r.activity_id=c.activity_id", "r.user_id=c.user_id",
        "r.registration_cycle_id=c.id", "r.status='VALID'").doesNotContain("SELECT *", "static_code_hash", "manual_reason");
    String routing = normalize(bound("selectSessionReference").getSql());
    assertThat(routing).contains("a.organization_id=s.organization_id", "a.id=s.activity_id", "s.id=?")
        .doesNotContain("static_code_hash", "SELECT *");
    String target = normalize(bound("selectRegistrationTarget").getSql());
    assertThat(target).contains("c.registration_id=?", "c.organization_id=?", "c.activity_id=?", "NOT EXISTS");
  }

  @Test
  void correctionSessionQueryIsTenantActivityScopedDeterministicAndDoesNotRequireEffectiveOpenState() {
    BoundSql query = bound("selectCorrectionSession");
    assertThat(normalize(query.getSql())).contains("organization_id=?", "activity_id=?",
        "ORDER BY opens_at DESC,id DESC LIMIT 1", "id AS session_id")
        .doesNotContain("static_code_hash", "SELECT *", "status='OPEN'", "opens_at <=", "closes_at >");
    assertThat(query.getParameterMappings()).extracting(p -> p.getProperty())
        .containsExactly("organizationId", "activityId");
  }

  private BoundSql bound(String method) {
    MybatisConfiguration config = new MybatisConfiguration();
    config.addMapper(AttendanceMapper.class);
    return config.getMappedStatement(AttendanceMapper.class.getName() + "." + method)
        .getBoundSql(Map.of("organizationId", 10L, "activityId", 11L, "userId", 21L,
            "sessionId", 101L, "registrationId", 201L, "now", now));
  }
  private String normalize(String sql) { return sql.replaceAll("\\s+", " ").trim(); }
  private void rejects(Runnable action, HttpStatus status, String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
      assertThat(ex.status()).isEqualTo(status); assertThat(ex.code()).isEqualTo(code);
    });
  }
}
