package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.*;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.*;
import com.volunteerflow.infrastructure.event.DomainEventPublisher;
import com.volunteerflow.infrastructure.ratelimit.RateLimitGuard;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import com.volunteerflow.organization.*;
import com.volunteerflow.rbac.*;
import com.volunteerflow.registration.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises all three real list controllers/services with real RBAC and mocked DB boundaries. */
class CoreListPaginationTest {
  private ActivityMapper activities;
  private ActivityPositionMapper positions;
  private OrganizationMemberMapper members;
  private RbacRolePermissionMapper grants;
  private RegistrationMapper registrations;
  private RegistrationCycleMapper cycles;
  private RegistrationAnswerMapper answers;
  private PromotionOfferMapper offers;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    activities = mock(ActivityMapper.class);
    positions = mock(ActivityPositionMapper.class);
    members = mock(OrganizationMemberMapper.class);
    grants = mock(RbacRolePermissionMapper.class);
    registrations = mock(RegistrationMapper.class);
    cycles = mock(RegistrationCycleMapper.class);
    answers = mock(RegistrationAnswerMapper.class);
    offers = mock(PromotionOfferMapper.class);
    var roles = mock(RbacRoleMapper.class);
    var role = new RbacRole();
    role.setId(8L);
    role.setOrganizationId(7L);
    role.setStatus("ACTIVE");
    var member = new OrganizationMember();
    member.setId(301L);
    member.setUserId(21L);
    member.setOrganizationId(7L);
    member.setRoleId(8L);
    when(members.selectActiveMember(21L, 7L)).thenReturn(member);
    when(roles.selectById(8L)).thenReturn(role);
    when(grants.countActivePermission(7L, 8L, "member:read")).thenReturn(1L);
    Activity first = activity(11L);
    Activity second = activity(12L);
    when(activities.selectPublishedPage(any(), eq(7L))).thenAnswer(call -> {
      IPage<Activity> page = call.getArgument(0);
      page.setTotal(101);
      return page.setRecords(page.getCurrent() == 99 ? List.of() : List.of(first, second));
    });
    var firstPosition = new ActivityPosition();
    firstPosition.setId(401L);
    firstPosition.setOrganizationId(7L);
    firstPosition.setActivityId(11L);
    firstPosition.setStatus("ACTIVE");
    when(positions.selectActiveByActivities(7L, List.of(11L, 12L))).thenReturn(List.of(firstPosition));
    when(members.selectActivePage(any(), eq(7L))).thenAnswer(call -> {
      IPage<OrganizationMember> page = call.getArgument(0);
      page.setTotal(205);
      return page.setRecords(page.getCurrent() == 99 ? List.of() : List.of(member));
    });
    var registration = new Registration();
    registration.setId(100L);
    registration.setUserId(21L);
    registration.setOrganizationId(7L);
    registration.setActivityId(11L);
    when(registrations.selectOwnedByUserPage(any(), eq(21L))).thenAnswer(call -> {
      IPage<Registration> page = call.getArgument(0);
      page.setTotal(37);
      return page.setRecords(page.getCurrent() == 99 ? List.of() : List.of(registration));
    });
    var cycle = new RegistrationCycle();
    cycle.setId(101L);
    cycle.setRegistrationId(100L);
    cycle.setUserId(21L);
    cycle.setOrganizationId(7L);
    cycle.setActivityId(11L);
    cycle.setPositionId(401L);
    cycle.setStatus("CONFIRMED");
    cycle.setAttendanceStatus("PENDING");
    when(cycles.selectActiveOrLatestOwned(21L, List.of(100L))).thenReturn(List.of(cycle));
    var authorization = new OrganizationAuthorizationService(members, roles, grants);
    var activityService = new ActivityService(activities, positions, mock(ActivityQuestionMapper.class),
        mock(ActivityPositionQuestionMapper.class), authorization, mock(AuditService.class), cycles,
        mock(DomainEventPublisher.class), mock(ActivityDetailCache.class),
        mock(ActivityDetailCacheInvalidator.class), Clock.systemUTC());
    var organizationService = new OrganizationService(mock(OrganizationMapper.class),
        mock(OrganizationInviteMapper.class), members, mock(AppUserMapper.class), roles,
        mock(RbacPermissionMapper.class), grants, authorization, mock(AuditService.class),
        mock(RefreshTokenGenerator.class), Clock.systemUTC());
    var queries = new RegistrationQueryService(registrations, cycles, answers, offers, positions,
        mock(AppUserMapper.class), authorization, new ObjectMapper());
    mvc = MockMvcBuilders.standaloneSetup(
        new ActivityController(activityService, mock(ActivityChangeService.class)),
        new OrganizationController(organizationService),
        new RegistrationController(mock(RegistrationSubmissionService.class), queries,
            mock(RegistrationCancellationService.class), mock(RegistrationReviewService.class),
            mock(PromotionService.class), mock(RateLimitGuard.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    var principal = new CurrentUser(21L, "member", "Member", "20260021", "contact", "USER");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activityDataArrayRemainsCompatibleAndPositionsLoadOnceForOnlyThePage() throws Exception {
    mvc.perform(get("/api/v1/organizations/7/activities"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data[0].activity.id").value(11))
        .andExpect(jsonPath("$.data[0].positions[0].id").value(401))
        .andExpect(jsonPath("$.data[1].positions").isEmpty())
        .andExpect(jsonPath("$.meta.total").value(101))
        .andExpect(jsonPath("$.meta.page").value(1))
        .andExpect(jsonPath("$.meta.size").value(20));
    verify(positions, times(1)).selectActiveByActivities(7L, List.of(11L, 12L));
    verify(positions, never()).selectActiveByActivity(any(), any());
    verify(activities, never()).selectPublished(any());
  }

  @Test
  void ownRegistrationPagingUsesPrincipalAndKeepsBatchProjection() throws Exception {
    mvc.perform(get("/api/v1/users/me/registrations").param("userId", "999"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].registrationId").value(100))
        .andExpect(jsonPath("$.data[0].attendanceStatus").value("PENDING"))
        .andExpect(jsonPath("$.meta.total").value(37));
    verify(registrations).selectOwnedByUserPage(any(), eq(21L));
    verify(cycles).selectActiveOrLatestOwned(21L, List.of(100L));
    verify(answers).selectByCycles(List.of(101L));
    verify(offers).selectPendingByCycles(List.of(101L));
    verify(cycles).selectWaitlistMetricsOwned(21L, List.of(101L));
    verify(cycles, never()).selectActiveOrLatest(any());
    verify(registrations, never()).selectOwnedByUser(any());
  }

  @Test
  void memberPagingPreservesArrayAndScopedTotal() throws Exception {
    mvc.perform(get("/api/v1/organizations/7/members"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(301))
        .andExpect(jsonPath("$.meta.total").value(205));
    verify(members, never()).selectActiveByOrganization(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/organizations/7/activities", "/api/v1/organizations/7/members",
      "/api/v1/users/me/registrations"})
  void clampsUpperAndLowerPageBounds(String url) throws Exception {
    mvc.perform(get(url).param("page", "0").param("size", "1000"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.meta.page").value(1))
        .andExpect(jsonPath("$.meta.size").value(100));
    mvc.perform(get(url).param("size", "0"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/organizations/7/activities", "/api/v1/organizations/7/members",
      "/api/v1/users/me/registrations"})
  void emptyHighPageRetainsDatabaseTotalAndSkipsDependentBatchReads(String url) throws Exception {
    mvc.perform(get(url).param("page", "99"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
        .andExpect(jsonPath("$.meta.page").value(99))
        .andExpect(result -> assertThat(new ObjectMapper().readTree(result.getResponse().getContentAsString())
            .path("meta").path("total").asLong()).isGreaterThan(0));
    verifyNoInteractions(positions, cycles, answers, offers);
  }

  @ParameterizedTest
  @ValueSource(strings = {"activities", "members"})
  void crossOrganizationIsHiddenBeforePaging(String suffix) throws Exception {
    mvc.perform(get("/api/v1/organizations/99/" + suffix))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    verify(activities, never()).selectPublishedPage(any(), any());
    verify(members, never()).selectActivePage(any(), any());
  }

  @Test
  void memberReadPermissionIsStillRequired() throws Exception {
    when(grants.countActivePermission(7L, 8L, "member:read")).thenReturn(0L);
    mvc.perform(get("/api/v1/organizations/7/members"))
        .andExpect(status().isForbidden());
    verify(members, never()).selectActivePage(any(), any());
  }

  private Activity activity(Long id) {
    var activity = new Activity();
    activity.setId(id);
    activity.setOrganizationId(7L);
    activity.setStatus("PUBLISHED");
    return activity;
  }
}
