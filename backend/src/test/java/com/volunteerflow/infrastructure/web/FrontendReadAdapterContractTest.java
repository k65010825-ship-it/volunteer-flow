package com.volunteerflow.infrastructure.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.volunteerflow.activity.*;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.organization.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Local HTTP adapter/JSON contract, not a deployed JWT-filter or database integration test. */
class FrontendReadAdapterContractTest {
  private final OrganizationService organizations = mock(OrganizationService.class);
  private final ActivityQuestionService questions = mock(ActivityQuestionService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var builder = new Jackson2ObjectMapperBuilder();
    new JacksonConfig().longAsStringCustomizer().customize(builder);
    mvc = MockMvcBuilders.standaloneSetup(new OrganizationController(organizations),
        new ActivityQuestionController(questions, mock(ActivityRegistrationPolicyService.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    var user = new CurrentUser(1L, "member", null, null, null, "USER");
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void cleanup() { SecurityContextHolder.clearContext(); }

  @Test
  void accessAndRolePermissionsUseDataEnvelopeAndStringIds() throws Exception {
    when(organizations.access(1L, 100L)).thenReturn(new OrganizationService.OrganizationAccess(
        100L, 8L, 30L, "Reader", List.of("activity:read")));
    when(organizations.rolePermissionCodes(1L, 100L, 30L)).thenReturn(List.of("activity:read"));
    mvc.perform(get("/api/v1/organizations/100/access")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.organizationId").value("100"))
        .andExpect(jsonPath("$.data.memberId").value("8"))
        .andExpect(jsonPath("$.data.roleId").value("30"))
        .andExpect(jsonPath("$.data.roleName").value("Reader"))
        .andExpect(jsonPath("$.data.permissionCodes[0]").value("activity:read"));
    mvc.perform(get("/api/v1/organizations/100/roles/30/permissions"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("activity:read"));
  }

  @Test
  void questionRoutesExposePersistedDefinitionFieldsWithoutAnswerData() throws Exception {
    ActivityQuestion common = new ActivityQuestion();
    common.setId(70L);
    common.setQuestionType("SINGLE_CHOICE");
    common.setTitle("Training");
    common.setRequiredQuestion(true);
    common.setOptionsJson("[\"yes\",\"no\"]");
    common.setSortOrder(2);
    ActivityPositionQuestion specific = new ActivityPositionQuestion();
    specific.setId(80L);
    specific.setPositionId(20L);
    when(questions.activityQuestions(1L, 10L)).thenReturn(List.of(common));
    when(questions.positionQuestions(1L, 20L)).thenReturn(List.of(specific));
    mvc.perform(get("/api/v1/activities/10/questions")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("70"))
        .andExpect(jsonPath("$.data[0].questionType").value("SINGLE_CHOICE"))
        .andExpect(jsonPath("$.data[0].requiredQuestion").value(true))
        .andExpect(jsonPath("$.data[0].optionsJson").value("[\"yes\",\"no\"]"))
        .andExpect(jsonPath("$.data[0].sortOrder").value(2))
        .andExpect(jsonPath("$.data[0].answers").doesNotExist());
    mvc.perform(get("/api/v1/positions/20/questions")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("80"))
        .andExpect(jsonPath("$.data[0].positionId").value("20"));
  }
}
