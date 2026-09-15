package com.volunteerflow.infrastructure.security;

import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.auth.CurrentUser;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

public class DatabaseJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {
  private final AppUserMapper userMapper;

  public DatabaseJwtAuthenticationConverter(AppUserMapper userMapper) {
    this.userMapper = userMapper;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Long userId;
    try {
      userId = Long.valueOf(jwt.getSubject());
    } catch (RuntimeException exception) {
      throw invalidToken("JWT subject is invalid");
    }
    AppUser user = userMapper.selectById(userId);
    if (user == null || !"ACTIVE".equals(user.getStatus())) {
      throw invalidToken("User is unavailable");
    }
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getPlatformRole()));
    return new UsernamePasswordAuthenticationToken(CurrentUser.from(user), jwt, authorities);
  }

  private OAuth2AuthenticationException invalidToken(String description) {
    return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", description, null));
  }
}
