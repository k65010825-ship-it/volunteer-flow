package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.audit.*;
import com.volunteerflow.checkin.ActivityFinalizationService;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.*;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

/** Real HTTP/service/RBAC/audit/transaction advice; database rows use a bounded model. */
class ActivityCompletionTest {
  private ActivityMapper activities;
  private RegistrationCycleMapper cycles;
  private AuditLogMapper audits;
  private OrganizationMemberMapper members;
  private RbacRolePermissionMapper grants;
  private ActivityDetailCacheInvalidator cache;
  private Activity activity;
  private final List<AuditLog> storedAudits = new ArrayList<>();
  private final Map<Long, String> attendance = new LinkedHashMap<>();
  private final LocalDateTime plannedEnd = LocalDateTime.of(2099, 10, 23, 18, 0);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    activities = mock(ActivityMapper.class);
    cycles = mock(RegistrationCycleMapper.class);
    audits = mock(AuditLogMapper.class);
    members = mock(OrganizationMemberMapper.class);
    grants = mock(RbacRolePermissionMapper.class);
    cache = mock(ActivityDetailCacheInvalidator.class);
    var roles = mock(RbacRoleMapper.class);
    var member = new OrganizationMember();
    member.setRoleId(8L);
    var role = new RbacRole();
    role.setId(8L);
    role.setOrganizationId(100L);
    role.setStatus("ACTIVE");
    when(members.selectActiveMember(21L, 100L)).thenReturn(member);
    when(roles.selectById(8L)).thenReturn(role);
    when(grants.countActivePermission(100L, 8L, "activity:change")).thenReturn(1L);
    activity = new Activity();
    activity.setId(11L);
    activity.setOrganizationId(100L);
    activity.setStatus("PUBLISHED");
    activity.setVersion(2);
    activity.setActivityEndAt(plannedEnd);
    when(activities.selectByIdForUpdate(11L)).thenReturn(activity);
    when(activities.claimFinishedManually(11L, 100L)).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      activity.setStatus("FINISHED");
      return 1;
    });
    attendance.put(101L, "PENDING");
    attendance.put(102L, "PENDING");
    when(cycles.countCurrentConfirmedForActivity(100L, 11L)).thenReturn(2L);
    when(cycles.finalizeCurrentConfirmedAttendance(100L, 11L)).thenAnswer(call -> {
      attendance.put(101L, "CHECKED_IN");
      attendance.put(102L, "NO_SHOW");
      return 2;
    });
    when(audits.insert(any(AuditLog.class))).thenAnswer(call -> {
      storedAudits.add(call.getArgument(0));
      return 1;
    });
    var finalizer = new ActivityFinalizationService(activities, cycles,
        new AuditService(audits, new ObjectMapper()), cache);
    var target = new ActivityCompletionService(activities,
        new OrganizationAuthorizationService(members, roles, grants), finalizer);
    var proxyFactory = new ProxyFactory(target);
    proxyFactory.addAdvice(new TransactionInterceptor(new ModelTransactions(),
        new AnnotationTransactionAttributeSource()));
    var service = (ActivityCompletionService) proxyFactory.getProxy();
    mvc = MockMvcBuilders.standaloneSetup(new ActivityCompletionController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    var principal = new CurrentUser(21L, "manager", "Manager", "20260021", "contact", "USER");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void finishesBeforePlannedEndAndArchivesAttendanceWithActorAndReason() throws Exception {
    complete().andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FINISHED"))
        .andExpect(jsonPath("$.data.version").value(3));
    assertThat(activity.getActivityEndAt()).isEqualTo(plannedEnd);
    assertThat(attendance).containsEntry(101L, "CHECKED_IN").containsEntry(102L, "NO_SHOW");
    assertThat(storedAudits).singleElement().satisfies(log -> {
      assertThat(log.getActorUserId()).isEqualTo(21L);
      assertThat(log.getOrganizationId()).isEqualTo(100L);
      assertThat(log.getAction()).isEqualTo("activity.finished");
      assertThat(log.getReason()).isEqualTo("天气变化");
      assertThat(log.getAfterJson()).contains("FINISHED", "confirmedCount");
    });
    verify(activities, never()).claimFinished(any(), any());
    verify(cache).evictAfterCommit(11L);
  }

  @Test
  void repeatedAuthorizedCompletionDoesNotRepeatSideEffects() throws Exception {
    complete().andExpect(status().isOk());
    complete().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
    assertThat(storedAudits).hasSize(1);
    verify(activities, times(1)).claimFinishedManually(11L, 100L);
    verify(cycles, times(1)).finalizeCurrentConfirmedAttendance(100L, 11L);
    verify(cache, times(1)).evictAfterCommit(11L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "CANCELED"})
  void rejectsOtherLifecycleStatesWithoutWriting(String status) throws Exception {
    activity.setStatus(status);
    complete().andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ACTIVITY_COMPLETION_NOT_ALLOWED"));
    verifyNoInteractions(cycles, audits, cache);
    verify(activities, never()).claimFinishedManually(any(), any());
  }

  @Test
  void missingActivityReturns404() throws Exception {
    when(activities.selectByIdForUpdate(11L)).thenReturn(null);
    complete().andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    verifyNoInteractions(cycles, audits, cache);
  }

  @Test
  void crossOrganizationIsHiddenEvenForAlreadyFinishedActivity() throws Exception {
    activity.setOrganizationId(200L);
    activity.setStatus("FINISHED");
    complete().andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    verifyNoInteractions(cycles, audits, cache);
  }

  @Test
  void requiresActivityChangePermissionWithoutTrustingUserIdParameter() throws Exception {
    when(grants.countActivePermission(100L, 8L, "activity:change")).thenReturn(0L);
    complete().andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    verify(activities, never()).claimFinishedManually(any(), any());
    verifyNoInteractions(cycles, audits, cache);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void requiresReason(String reason) throws Exception {
    mvc.perform(post("/api/v1/activities/11/completion").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(activities, cycles, audits, cache);
  }

  @Test
  void rejectsOverlongReason() throws Exception {
    mvc.perform(post("/api/v1/activities/11/completion").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + "a".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(activities, cycles, audits, cache);
  }

  @Test
  void auditFailureRollsBackLifecycleAndAttendance() throws Exception {
    when(audits.insert(any(AuditLog.class))).thenReturn(0);
    complete().andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    assertThat(activity.getStatus()).isEqualTo("PUBLISHED");
    assertThat(activity.getVersion()).isEqualTo(2);
    assertThat(attendance).containsEntry(101L, "PENDING").containsEntry(102L, "PENDING");
    verifyNoInteractions(cache);
  }

  @Test
  void countMismatchRollsBackWithoutAudit() throws Exception {
    when(cycles.finalizeCurrentConfirmedAttendance(100L, 11L)).thenReturn(1);
    complete().andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    assertThat(activity.getStatus()).isEqualTo("PUBLISHED");
    verifyNoInteractions(audits, cache);
  }

  private org.springframework.test.web.servlet.ResultActions complete() throws Exception {
    return mvc.perform(post("/api/v1/activities/11/completion").param("userId", "999")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" 天气变化 \"}"));
  }

  /** In-memory rollback model, not evidence of live MySQL rollback. */
  private final class ModelTransactions extends AbstractPlatformTransactionManager {
    private String oldStatus;
    private Integer oldVersion;
    private Map<Long, String> oldAttendance;
    private int oldAuditCount;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      oldStatus = activity.getStatus();
      oldVersion = activity.getVersion();
      oldAttendance = new LinkedHashMap<>(attendance);
      oldAuditCount = storedAudits.size();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      activity.setStatus(oldStatus);
      activity.setVersion(oldVersion);
      attendance.clear();
      attendance.putAll(oldAttendance);
      storedAudits.subList(oldAuditCount, storedAudits.size()).clear();
    }
  }
}
