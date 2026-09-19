package com.volunteerflow.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.auth.AuthController;
import com.volunteerflow.auth.AuthService;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.registration.PromotionService;
import com.volunteerflow.registration.RegistrationCancellationService;
import com.volunteerflow.registration.RegistrationController;
import com.volunteerflow.registration.RegistrationQueryService;
import com.volunteerflow.registration.RegistrationReviewService;
import com.volunteerflow.registration.RegistrationSubmissionService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(JwtSecurityFlowTest.FlowConfig.class)
class JwtSecurityFlowTest {
  @Autowired
  MockMvc mvc;

  @Autowired
  AppUserMapper userMapper;

  @Autowired
  AuthService authService;

  @Autowired
  JwtTokenService tokenService;

  @Autowired
  RegistrationSubmissionService submissions;

  @BeforeEach
  void resetMocks() {
    reset(userMapper, authService, submissions);
  }

  @Test
  void validJwtGetsCurrentUserThroughSecurityFilter() throws Exception {
    AppUser user = user("ACTIVE");
    when(userMapper.selectById(1L)).thenReturn(user);
    when(authService.me(1L)).thenReturn(CurrentUser.from(user));

    mvc.perform(
            get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenService.issue(user).value())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("student1"))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
  }

  @Test
  void tamperedJwtAndDisabledUserCannotAccessMe() throws Exception {
    AppUser user = user("ACTIVE");
    String token = tokenService.issue(user).value();
    String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

    mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
        .andExpect(status().isUnauthorized());

    when(userMapper.selectById(1L)).thenReturn(user("DISABLED"));
    mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRequiresJwt() throws Exception {
    mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
  }

  @Test
  void registrationUsesVerifiedJwtIdentityRatherThanBodyIdentity() throws Exception {
    AppUser user = user("ACTIVE");
    when(userMapper.selectById(1L)).thenReturn(user);
    when(submissions.submit(eq(1L), eq(10L), any()))
        .thenReturn(
            new RegistrationSubmissionService.RegistrationResult(
                30L, 40L, "CONFIRMED", null, null));
    mvc.perform(
            post("/api/v1/activities/10/registrations")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenService.issue(user).value()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionId\":20,\"answers\":[],\"userId\":999}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.registrationId").value(30));
    verify(submissions)
        .submit(
            1L, 10L, new RegistrationSubmissionService.SubmitRegistrationRequest(20L, List.of()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/registrations/30",
        "/api/v1/users/me/registrations",
        "/api/v1/positions/20/registrations"
      })
  void anonymousRegistrationReadsRequireJwt(String path) throws Exception {
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/activities/10/registrations", "/api/v1/registrations/30/cancellation",
        "/api/v1/promotion-offers/50/responses", "/api/v1/registrations/30/review-decisions",
        "/api/v1/registrations/30/promotion-offers"
      })
  void anonymousRegistrationWritesRequireJwt(String path) throws Exception {
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private static AppUser user(String status) {
    AppUser user = new AppUser();
    user.setId(1L);
    user.setUsername("student1");
    user.setRealName("张三");
    user.setStudentNumber("20260001");
    user.setContact("contact");
    user.setPlatformRole("USER");
    user.setStatus(status);
    return user;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FlowConfig {
    @Bean
    RegistrationController flowRegistrationController(RegistrationSubmissionService submissions) {
      return new RegistrationController(
          submissions,
          mock(RegistrationQueryService.class),
          mock(RegistrationCancellationService.class),
          mock(RegistrationReviewService.class),
          mock(PromotionService.class));
    }

    @Bean
    RegistrationSubmissionService flowSubmissionService() {
      return mock(RegistrationSubmissionService.class);
    }

    @Bean
    AppUserMapper flowUserMapper() {
      return mock(AppUserMapper.class);
    }

    @Bean
    AuthService flowAuthService() {
      return mock(AuthService.class);
    }

    @Bean
    AuthController flowAuthController(AuthService service) {
      return new AuthController(service, false);
    }

    @Bean
    JwtProperties flowJwtProperties() {
      JwtProperties properties = new JwtProperties();
      properties.setIssuer("volunteerflow");
      properties.setAudience("volunteerflow-web");
      properties.setAccessTokenTtl(Duration.ofMinutes(30));
      return properties;
    }

    @Bean
    SecretKey flowSecretKey() {
      return new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
    }

    @Bean
    JwtDecoder flowJwtDecoder(SecretKey key, JwtProperties properties) {
      return new JwtInfrastructureConfig().jwtDecoder(key, properties);
    }

    @Bean
    JwtTokenService flowTokenService(SecretKey key, JwtProperties properties) {
      return new JwtTokenService(
          new JwtInfrastructureConfig().jwtEncoder(key), properties, Clock.systemUTC());
    }

    @Bean
    DatabaseJwtAuthenticationConverter flowConverter(AppUserMapper mapper) {
      return new DatabaseJwtAuthenticationConverter(mapper);
    }
  }
}
