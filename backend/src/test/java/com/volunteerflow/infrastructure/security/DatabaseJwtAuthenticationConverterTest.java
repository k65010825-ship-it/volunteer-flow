package com.volunteerflow.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.auth.CurrentUser;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class DatabaseJwtAuthenticationConverterTest {
  @Test
  void invalidSubjectIsRejectedAsAnAuthenticationFailure() {
    assertThatThrownBy(() -> converter.convert(jwt("not-an-id")))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  @Test
  void programmingFailureIsNotDisguisedAsInvalidCredentials() {
    Jwt broken = mock(Jwt.class);
    IllegalStateException failure = new IllegalStateException("invalid JWT wiring");
    when(broken.getSubject()).thenThrow(failure);
    assertThatThrownBy(() -> converter.convert(broken)).isSameAs(failure);
  }

  @Test
  void passwordChangeRejectsLegacyJwtImmediately() {
    AppUser changed = user("ACTIVE");
    changed.setVersion(1);
    when(userMapper.selectById(1L)).thenReturn(changed);
    assertThatThrownBy(() -> converter.convert(jwt("1")))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  private final AppUserMapper userMapper = mock(AppUserMapper.class);
  private final DatabaseJwtAuthenticationConverter converter =
      new DatabaseJwtAuthenticationConverter(userMapper);

  @Test
  void acceptsNewCredentialGenerationAndRejectsStaleOrMalformedClaims() {
    AppUser changed = user("ACTIVE");
    changed.setVersion(1);
    when(userMapper.selectById(1L)).thenReturn(changed);
    var fresh =
        Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject("1")
            .claim("credential_version", 1)
            .build();
    assertThat(converter.convert(fresh).getPrincipal()).isInstanceOf(CurrentUser.class);
    for (Object value : new Object[] {0, "1", 1.5, -1}) {
      var rejected =
          Jwt.withTokenValue("token")
              .header("alg", "HS256")
              .subject("1")
              .claim("credential_version", value)
              .build();
      assertThatThrownBy(() -> converter.convert(rejected))
          .isInstanceOf(OAuth2AuthenticationException.class);
    }
  }

  @Test
  void loadsCurrentUserAndAuthorityFromDatabase() {
    AppUser user = user("ACTIVE");
    when(userMapper.selectById(1L)).thenReturn(user);

    var authentication = converter.convert(jwt("1"));

    assertThat(authentication.getPrincipal()).isInstanceOf(CurrentUser.class);
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_USER");
  }

  @Test
  void disabledUserIsRejectedEvenWhenJwtIsValid() {
    when(userMapper.selectById(1L)).thenReturn(user("DISABLED"));

    assertThatThrownBy(() -> converter.convert(jwt("1")))
        .isInstanceOf(OAuth2AuthenticationException.class);
  }

  private Jwt jwt(String subject) {
    Instant now = Instant.now();
    return new Jwt(
        "token", now, now.plusSeconds(60), Map.of("alg", "HS256"), Map.of("sub", subject));
  }

  private AppUser user(String status) {
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
}
