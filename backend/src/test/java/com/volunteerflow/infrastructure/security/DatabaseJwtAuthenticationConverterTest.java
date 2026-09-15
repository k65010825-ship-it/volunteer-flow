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
  private final AppUserMapper userMapper = mock(AppUserMapper.class);
  private final DatabaseJwtAuthenticationConverter converter =
      new DatabaseJwtAuthenticationConverter(userMapper);

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
