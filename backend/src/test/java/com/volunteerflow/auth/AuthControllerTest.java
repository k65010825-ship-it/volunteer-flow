package com.volunteerflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.infrastructure.web.ApiResponse;
import com.volunteerflow.infrastructure.ratelimit.RateLimitExceededException;
import com.volunteerflow.infrastructure.ratelimit.RateLimitGuard;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
  private final AuthService authService = mock(AuthService.class);
  private final RateLimitGuard rateLimits = mock(RateLimitGuard.class);
  private final AuthController controller = new AuthController(authService, rateLimits, false);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(controller)
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void loginUsesServletRemoteAddressAndUsernameBeforeService() throws Exception {
    LoginRequest request = new LoginRequest(" Alice ", "Strong-password-123");
    when(authService.login(request, "JUnit"))
        .thenReturn(
            new AuthTokens("access-token", "refresh-token", Instant.parse("2026-09-14T08:30:00Z")));

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(
                    servletRequest -> {
                      servletRequest.setRemoteAddr("203.0.113.9");
                      return servletRequest;
                    })
                .header("User-Agent", "JUnit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\" Alice \",\"password\":\"Strong-password-123\"}"))
        .andExpect(status().isOk());

    var order = inOrder(rateLimits, authService);
    order.verify(rateLimits).checkLogin("203.0.113.9", " Alice ");
    order.verify(authService).login(request, "JUnit");
  }

  @Test
  void loginIgnoresClientSuppliedForwardedChain() throws Exception {
    when(authService.login(any(), any()))
        .thenReturn(
            new AuthTokens("access-token", "refresh-token", Instant.parse("2026-09-14T08:30:00Z")));

    mockMvc.perform(
        post("/api/v1/auth/login")
            .with(
                servletRequest -> {
                  servletRequest.setRemoteAddr("198.51.100.17");
                  return servletRequest;
                })
            .header("X-Forwarded-For", "10.0.0.1, 10.0.0.2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice\",\"password\":\"Strong-password-123\"}"))
        .andExpect(status().isOk());

    verify(rateLimits).checkLogin("198.51.100.17", "alice");
  }

  @Test
  void rejectedLoginDoesNotCallAuthenticationService() throws Exception {
    doThrow(new RateLimitExceededException(5))
        .when(rateLimits)
        .checkLogin("203.0.113.9", "alice");

    mockMvc.perform(
        post("/api/v1/auth/login")
            .with(
                servletRequest -> {
                  servletRequest.setRemoteAddr("203.0.113.9");
                  return servletRequest;
                })
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"alice\",\"password\":\"Strong-password-123\"}"))
        .andExpect(status().isTooManyRequests());

    verifyNoInteractions(authService);
  }

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
    var response = controller.logout(user, "current-refresh");

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

    ApiResponse<CurrentUser> response = controller.me(user);

    assertThat(response.data()).isEqualTo(user);
    verify(authService).me(1L);
  }
}
