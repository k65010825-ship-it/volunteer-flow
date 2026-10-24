package com.volunteerflow.infrastructure.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.volunteerflow.infrastructure.persistence.AcceptanceDatabaseGuard.Guard;
import com.volunteerflow.infrastructure.web.AcceptanceIdentityController;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AcceptanceIdentityController.class)
@ActiveProfiles("acceptance")
@Import(SecurityConfig.class)
class AcceptanceIdentitySecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean Guard guard;
  @MockitoBean DataSource source;

  @Test
  void anonymousGetOnlyExposesVerifiedNonSecretIdentity() throws Exception {
    when(guard.database()).thenReturn("vf_acceptance_0123456789abcdef0123456789abcdef");
    when(guard.maintenanceDisabled()).thenReturn(true);
    when(guard.consumerDisabled()).thenReturn(true);
    mvc.perform(get("/api/v1/acceptance/identity"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.database").value("vf_acceptance_0123456789abcdef0123456789abcdef"))
        .andExpect(jsonPath("$.data.maintenanceDisabled").value(true));
  }

  @Test
  void anonymousPostIsNeverAllowed() throws Exception {
    mvc.perform(post("/api/v1/acceptance/identity")).andExpect(status().isUnauthorized());
  }
}
