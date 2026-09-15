package com.volunteerflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.infrastructure.web.ApiResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new AuthController(authService, false)).build();

  @Test
  void registerReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
    when(authService.register(any(), eq("JUnit")))
        .thenReturn(
            new AuthTokens("access-token", "refresh-token", Instant.parse("2026-09-14T08:30:00Z")));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .header("User-Agent", "JUnit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"student1","password":"Strong-password-123",
                     "realName":"张三","studentNumber":"20260001","contact":"contact"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.accessToken").value("access-token"))
        .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
        .andExpect(
            header()
                .string(
                    "Set-Cookie",
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("refresh_token=refresh-token"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))));
  }

  @Test
  void invalidRegistrationIsRejectedBeforeServiceCall() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refreshRotatesCookieAndReturnsNewAccessToken() throws Exception {
    when(authService.refresh("old-refresh", "JUnit"))
        .thenReturn(
            new AuthTokens("new-access", "new-refresh", Instant.parse("2026-09-14T08:30:00Z")));

    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("refresh_token", "old-refresh"))
                .header("User-Agent", "JUnit"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accessToken").value("new-access"))
        .andExpect(
            header()
                .string(
                    "Set-Cookie",
                    org.hamcrest.Matchers.containsString("refresh_token=new-refresh")));
  }

  @Test
  void logoutRevokesCurrentSessionAndClearsCookie() {
    CurrentUser user = new CurrentUser(1L, "student1", "张三", "20260001", "contact", "USER");
    var response = new AuthController(authService, false).logout(user, "current-refresh");

    assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.NO_CONTENT);
    assertThat(response.getHeaders().getFirst("Set-Cookie"))
        .contains("refresh_token=")
        .contains("Max-Age=0");
    verify(authService).logout(1L, "current-refresh");
  }

  @Test
  void meReturnsAuthenticatedUser() {
    CurrentUser user = new CurrentUser(1L, "student1", "张三", "20260001", "contact", "USER");
    when(authService.me(1L)).thenReturn(user);

    ApiResponse<CurrentUser> response = new AuthController(authService, false).me(user);

    assertThat(response.data()).isEqualTo(user);
    verify(authService).me(1L);
  }
}
