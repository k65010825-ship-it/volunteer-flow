package com.volunteerflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.rbac.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises real HTTP, query and RBAC code, mocking only database boundaries. */
class AuditLogQueryTest {
  private AuditLogMapper logs;
  private RbacRolePermissionMapper grants;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    logs = mock(AuditLogMapper.class);
    var members = mock(OrganizationMemberMapper.class);
    var roles = mock(RbacRoleMapper.class);
    grants = mock(RbacRolePermissionMapper.class);
    var member = new OrganizationMember();
    member.setRoleId(8L);
    when(members.selectActiveMember(21L, 100L)).thenReturn(member);
    var role = new RbacRole();
    role.setId(8L);
    role.setOrganizationId(100L);
    role.setStatus("ACTIVE");
    when(roles.selectById(8L)).thenReturn(role);
    when(grants.countActivePermission(100L, 8L, "audit:read")).thenReturn(1L);
    when(logs.selectForOrganization(any(), eq(100L), any())).thenAnswer(invocation -> {
      IPage<AuditLog> page = invocation.getArgument(0);
      var row = new AuditLog();
      row.setId(900L);
      row.setOrganizationId(100L);
      row.setAction("position.changed");
      row.setResourceType("activity_position");
      row.setBeforeJson("{\"capacity\":3,\"password\":\"secret\",\"description\":\"contact\"}");
      row.setAfterJson("{\"capacity\":4,\"token\":\"secret\",\"description\":\"private\"}");
      row.setReason("secret free text");
      page.setRecords(List.of(row));
      page.setTotal(1);
      return page;
    });
    var service = new AuditLogQueryService(logs,
        new OrganizationAuthorizationService(members, roles, grants), new ObjectMapper());
    mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    var user = new CurrentUser(21L, "member", "Member", "20260021", "contact", "USER");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsSafeSnapshotsAndDefaultPagination() throws Exception {
    mvc.perform(get("/api/v1/organizations/100/audit-logs").param("userId", "999"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(900))
        .andExpect(jsonPath("$.data[0].before.capacity").value(3))
        .andExpect(jsonPath("$.data[0].after.capacity").value(4))
        .andExpect(jsonPath("$.data[0].changedFields.length()").value(2))
        .andExpect(jsonPath("$.data[0].changedFields[0]").value("capacity"))
        .andExpect(jsonPath("$.data[0].changedFields[1]").value("description"))
        .andExpect(jsonPath("$.data[0].before.password").doesNotExist())
        .andExpect(jsonPath("$.data[0].after.token").doesNotExist())
        .andExpect(jsonPath("$.data[0].after.description").doesNotExist())
        .andExpect(jsonPath("$.data[0].reason").doesNotExist())
        .andExpect(jsonPath("$.meta.page").value(1))
        .andExpect(jsonPath("$.meta.size").value(20))
        .andExpect(jsonPath("$.meta.total").value(1));
  }

  @Test
  void deniesMemberWithoutPermissionBeforeReadingLogs() throws Exception {
    when(grants.countActivePermission(100L, 8L, "audit:read")).thenReturn(0L);
    mvc.perform(get("/api/v1/organizations/100/audit-logs"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    verify(logs, never()).selectForOrganization(any(), any(), any());
  }

  @Test
  void hidesOtherOrganizationBeforeReadingLogs() throws Exception {
    mvc.perform(get("/api/v1/organizations/200/audit-logs"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
    verify(logs, never()).selectForOrganization(any(), any(), any());
  }

  @Test
  void clampsPaginationAndBindsEveryFilter() throws Exception {
    mvc.perform(get("/api/v1/organizations/100/audit-logs")
            .param("page", "0").param("size", "1000").param("actorUserId", "21")
            .param("action", " position.changed ").param("resourceType", "activity_position")
            .param("resourceId", "50").param("from", "2026-10-01T00:00:00")
            .param("to", "2026-11-01T00:00:00"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(100))
        .andExpect(jsonPath("$.meta.page").value(1));
    verify(logs).selectForOrganization(argThat(page -> page.getCurrent() == 1 && page.getSize() == 100),
        eq(100L), argThat(filter -> filter.actorUserId().equals(21L)
            && filter.action().equals("position.changed") && filter.resourceId().equals(50L)
            && filter.resourceType().equals("activity_position")
            && filter.from().equals(LocalDateTime.of(2026, 10, 1, 0, 0))
            && filter.to().equals(LocalDateTime.of(2026, 11, 1, 0, 0))));
  }

  @Test
  void rejectsInvalidTimeRangeWithoutReadingLogs() throws Exception {
    mvc.perform(get("/api/v1/organizations/100/audit-logs")
            .param("from", "2026-11-01T00:00:00").param("to", "2026-10-01T00:00:00"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIT_FILTER"));
    verify(logs, never()).selectForOrganization(any(), any(), any());
  }

  @Test
  void clampsSizeToOne() throws Exception {
    mvc.perform(get("/api/v1/organizations/100/audit-logs").param("size", "-1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(1));
  }

  @Test
  void offersNoMutationEndpoint() throws Exception {
    mvc.perform(post("/api/v1/organizations/100/audit-logs"))
        .andExpect(status().isMethodNotAllowed());
    verify(logs, never()).selectForOrganization(any(), any(), any());
  }

  @Test
  void keepsOnlyTypedWhitelistedValuesAndSummarizesFreeText() throws Exception {
    snapshotResponse("{\"capacity\":{\"token\":\"secret\"},\"status\":\"secret\","
        + "\"serviceStartAt\":\"secret\",\"method\":\"SELF_SERVICE\",\"contact\":\"123\"}", "null");
    mvc.perform(get("/api/v1/organizations/100/audit-logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].before.capacity").doesNotExist())
        .andExpect(jsonPath("$.data[0].before.status").doesNotExist())
        .andExpect(jsonPath("$.data[0].before.serviceStartAt").doesNotExist())
        .andExpect(jsonPath("$.data[0].before.contact").doesNotExist())
        .andExpect(jsonPath("$.data[0].before.method").value("SELF_SERVICE"))
        .andExpect(jsonPath("$.data[0].after").isEmpty());
  }

  @Test
  void malformedHistoricalSnapshotsDoNotBreakPageOrExposeRawText() throws Exception {
    snapshotResponse("not-json-secret", "[\"secret\"]");
    mvc.perform(get("/api/v1/organizations/100/audit-logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].before").isEmpty())
        .andExpect(jsonPath("$.data[0].after").isEmpty())
        .andExpect(jsonPath("$.data[0].changedFields").isEmpty());
  }

  @Test
  void rejectsNonPositiveIdsAndOverlongAction() throws Exception {
    for (String[] pair : List.of(new String[]{"actorUserId", "0"},
        new String[]{"resourceId", "-1"}, new String[]{"action", "a".repeat(97)})) {
      mvc.perform(get("/api/v1/organizations/100/audit-logs").param(pair[0], pair[1]))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_AUDIT_FILTER"));
    }
    verify(logs, never()).selectForOrganization(any(), any(), any());
  }

  private void snapshotResponse(String before, String after) {
    when(logs.selectForOrganization(any(), eq(100L), any())).thenAnswer(invocation -> {
      IPage<AuditLog> page = invocation.getArgument(0);
      var row = new AuditLog();
      row.setId(900L);
      row.setOrganizationId(100L);
      row.setBeforeJson(before);
      row.setAfterJson(after);
      return page.setRecords(List.of(row));
    });
  }
}
